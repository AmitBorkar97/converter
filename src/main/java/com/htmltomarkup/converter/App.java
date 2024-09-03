package com.htmltomarkup.converter;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.apache.commons.io.FilenameUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.*;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.*;


public class App {
    public static void main(String[] args) {
        FlexmarkHtmlConverter converter = new FlexmarkHtmlConverter.Builder().build();
        String filePath = ConversionTask.chooseFile(); // Ensure this method is properly synchronized

        if (filePath == null) {
            System.out.println("No file selected. Exiting...");
            return;
        }

        // Determine the number of tasks (lines in the file)
        int numTasks = ConversionTask.countLinesInFile (filePath);
        if (numTasks == 0) {
            System.out.println("File is empty. Exiting...");
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(numTasks, 5)); // Use the minimum of 5 or numTasks for thread pool size
        CountDownLatch latch = new CountDownLatch(numTasks);

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Runnable task = new ConversionTask(line, converter, latch);
                executor.execute(task);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        executor.shutdown();
        try {
            // Wait for all tasks to complete
            latch.await();
            ConversionTask.showPopupMessage("All files have been saved to: " + System.getProperty("user.dir"));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
    static class ConversionTask implements Runnable {
        private final String url;
        private final CountDownLatch latch;


        public ConversionTask(String url, FlexmarkHtmlConverter converter, CountDownLatch latch) {
            this.url = url;
            this.latch = latch;

        }

        @Override
        public void run() {
            try {
            String htmlContent = downloadHTML(url);
            extractAndSaveImages(url);
            String markdownContent = convertToMarkdown(htmlContent);

            // Update HTML content to remove existing image links and insert Markdown image links
            String updatedHtmlContent = updateHtmlContent(markdownContent);

            saveMarkdownFile(updatedHtmlContent);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }finally {
                // Decrement the latch count
                latch.countDown();
            }
        }


        private static String chooseFile() {
            final String[] filePath = new String[1];
            // Use a SwingWorker to ensure the dialog runs on the EDT and handle the result
            SwingUtilities.invokeLater(() -> {
                JFileChooser fileChooser = new JFileChooser();
                int returnValue = fileChooser.showOpenDialog(null);
                if (returnValue == JFileChooser.APPROVE_OPTION) {
                    filePath[0] = fileChooser.getSelectedFile().getPath();
                }
            });

            // Wait for the file chooser to complete and ensure it's not null
            while (filePath[0] == null) {
                try {
                    Thread.sleep(100); // Adjust the sleep duration as needed
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return filePath[0];
        }


        private void saveMarkdownFile(String markdownContent) throws IOException {
            String outputDirectory = System.getProperty("user.dir");

            // Create Markdown file
            File markdownFile = new File(outputDirectory, getFileNameFromURL(url) + ".md");

            // Write the Markdown content to the file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(markdownFile))) {
                writer.write(markdownContent);
            }

            // Inform the user about the saved file
          //  showPopupMessage("Markdown file saved to: " + markdownFile.getAbsolutePath());
        }


        private static String downloadHTML(String url) {
            try {
                Document document = Jsoup.connect(url).get();
                return document.html();
            } catch (IOException e) {
                System.err.println("Failed to download HTML from URL: " + url);
                e.printStackTrace();
                return null;
            }
        }

        private static String convertToMarkdown(String htmlContent) {
            // Apply custom rules
            htmlContent = htmlContent.replaceAll("<p\\s+class=\"(MsoBodyTextIndent|pNoteCMT|pBullet|pBody|pNumList1CMT|pToC_Subhead[1-6])\">(.*?)</p>",
                    "$1 $2\n");
            htmlContent = htmlContent.replaceAll("MsoBodyTextIndent", "<br> > ");
            htmlContent = htmlContent.replaceAll("<p\\s+class=\"pNoteCMT\">(.*?)</p>",
                    "> [!$1]\n");
            htmlContent = htmlContent.replaceAll("pBullet", "- ");
            htmlContent = htmlContent.replaceAll("pBody", "");
            htmlContent = htmlContent.replaceAll("pNumList1CMT", "");

            // Convert pToC_Subhead to Markdown headings
            htmlContent = htmlContent.replaceAll("pToC_Subhead1", "<br># ");
            htmlContent = htmlContent.replaceAll("pToC_Subhead2", "<br>## ");
            htmlContent = htmlContent.replaceAll("pToC_Subhead3", "<br>### ");
            htmlContent = htmlContent.replaceAll("pSubhead3CMT",  "<br>### ");
            htmlContent = htmlContent.replaceAll("pToC_Subhead4", "<br>#### ");
            htmlContent = htmlContent.replaceAll("pToC_Subhead5", "<br>##### ");
            htmlContent = htmlContent.replaceAll("pToC_Subhead6", "<br>###### ");


            FlexmarkHtmlConverter converter = new FlexmarkHtmlConverter.Builder().build();
            return converter.convert(htmlContent);
        }

        private void extractAndSaveImages(String url) {
            try {
                // Extract image URLs from the HTML content
                List<String> imageUrls = extractImageUrls(url);

                // Create a directory to save the images
                String directoryPath = System.getProperty("user.dir") + File.separator + "images";
                File directory = new File(directoryPath);
                if (!directory.exists()) {
                    directory.mkdirs();
                }

                // Map to store the count of each filename
                Map<String, Integer> filenameCountMap = new HashMap<>();

                // Download and save each image
                for (String imageUrl : imageUrls) {
                    URL imgUrl = new URL(imageUrl);
                    String fileName = FilenameUtils.getName(imgUrl.getPath());

                    if (fileName.toLowerCase().contains("note") ||
                            fileName.toLowerCase().contains("tip") ||
                            FilenameUtils.getExtension(fileName).equalsIgnoreCase("gif")) {
                        System.out.println("Skipping image: " + fileName);
                        continue;
                    }

                    // Get the count of occurrences for the current filename
                    int count = filenameCountMap.getOrDefault(fileName, 0);

                    // Update the count for the current filename
                    filenameCountMap.put(fileName, count + 1);

                    // Append the count inside regular brackets to the filename if count is greater than 1
                    if (count > 0) {
                        String baseName = FilenameUtils.getBaseName(fileName);
                        String extension = FilenameUtils.getExtension(fileName);
                        fileName = baseName + "(" + count + ")." + extension;
                    }

                    // Download and save the image
                    File outputFile = new File(directory, fileName);
                    try (InputStream in = imgUrl.openStream();
                         OutputStream out = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    System.out.println("Image saved: " + outputFile.getAbsolutePath());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public static List<String> extractImageUrls(String url) throws IOException {
            Document document = Jsoup.connect(url).get();

            List<String> imageUrls = new ArrayList<>();

            // Extract image URLs from img elements
            Elements imgElements = document.select("img");
            for (Element img : imgElements) {
                String imageUrl = img.absUrl("src");
                if (!imageUrl.isEmpty()) {
                    imageUrls.add(imageUrl);
                }
            }

            return imageUrls;
        }


        private String updateHtmlContent(String htmlContent) {
            // Define the regular expression pattern to match Markdown image links with only one capturing group for the URL
            Pattern pattern = Pattern.compile("!\\[.*?\\]\\(([^\\)]*)\\)");

            // Create a matcher for the HTML content
            Matcher matcher = pattern.matcher(htmlContent);

            // Create a StringBuffer to hold the updated HTML content
            StringBuffer updatedHtmlContent = new StringBuffer();

            // Iterate through matches and replace them with the desired format
            while (matcher.find()) {
                String imageUrl = matcher.group(1); // Extract the image URL from the first capturing group
                String imageName = FilenameUtils.getName(imageUrl); // Get the filename from the URL
                String replacement = Matcher.quoteReplacement("!["
                        + imageName + "](https://www.cisco.com" + imageUrl + ")"); // Format the replacement string and escape special characters
                matcher.appendReplacement(updatedHtmlContent, replacement); // Replace the match with the formatted string
            }
            matcher.appendTail(updatedHtmlContent);

            return updatedHtmlContent.toString(); // Return the updated HTML content
        }




        private static void showPopupMessage(String message) {
            JOptionPane.showMessageDialog(null, message, "Execution Completed", JOptionPane.INFORMATION_MESSAGE);
        }

        private static String getFileNameFromURL(String url) {
            try {
                Document document = Jsoup.connect(url).get();
                String title = document.title().trim();
                // Remove any characters that are not suitable for file names
                title = title.replaceAll("[^a-zA-Z0-9-_\\.]", "");
                if (!title.isEmpty()) {
                    return title;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return "output";
        }
        // Method to count lines in the file
        private static int countLinesInFile(String filePath) {
            int lines = 0;
            try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
                while (reader.readLine() != null) {
                    lines++;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return lines;
        }

    }
}
