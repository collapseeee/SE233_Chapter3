package se233.chapter3.controller;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import se233.chapter3.Launcher;
import se233.chapter3.model.FileEntry;
import se233.chapter3.model.FileFreq;
import se233.chapter3.model.PdfDocument;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainViewController {
    LinkedHashMap<String, List<FileFreq>> uniqueSets;
    @FXML
    private ListView<FileEntry> inputListView;
    @FXML
    private Button startButton;
    @FXML
    private ListView listView;
    @FXML
    private MenuItem closeMenuItem;
    @FXML
    private MenuItem deleteMenuItem;

    @FXML
    public void initialize() {
        inputListView.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            final boolean isAccept = db.getFiles().get(0).getName().toLowerCase().endsWith(".pdf");
            if (db.hasFiles() && isAccept) {
                event.acceptTransferModes(TransferMode.COPY);
            } else {
                event.consume();
            }
        });
        inputListView.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                success = true;
                FileEntry fileEntry;
                int total_files = db.getFiles().size();
                WordCountMapTask[] wordCountMapTaskArray = new WordCountMapTask[total_files];
                Map<String, FileFreq>[] wordMap = new Map[total_files];
                for (int i = 0; i < total_files; i++) {
                    File file = db.getFiles().get(i);
                    fileEntry = new FileEntry(file.getName(), file.getAbsolutePath());
                    inputListView.getItems().add(fileEntry);
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
        startButton.setOnAction(event -> {
            Parent bgRoot = Launcher.primaryStage.getScene().getRoot();
            Task<Void> processTask = new Task<Void>() {
                @Override
                public Void call() throws IOException {
                    ProgressIndicator pi = new ProgressIndicator();
                    VBox box = new VBox(pi);
                    box.setAlignment(Pos.CENTER);
                    Launcher.primaryStage.getScene().setRoot(box);
                    ExecutorService executor = Executors.newFixedThreadPool(4);
                    final ExecutorCompletionService<Map<String,FileFreq>> completionService = new ExecutorCompletionService<>(executor);
                    List<FileEntry> inputListViewItems = inputListView.getItems();
                    int total_files = inputListViewItems.size();
                    Map<String, FileFreq>[] wordMap = new Map[total_files];
                    for (int i=0; i<total_files; i++) {
                        try {
                            String filePath = inputListViewItems.get(i).getFilePath();
                            PdfDocument p = new PdfDocument(filePath);
                            completionService.submit(new WordCountMapTask(p));
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    for (int i=0; i<total_files; i++) {
                        try {
                            Future<Map<String,FileFreq>> future = completionService.take();
                            wordMap[i] = future.get();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    try {
                        WordCountReduceTask merger = new WordCountReduceTask(wordMap);
                        Future<LinkedHashMap<String, List<FileFreq>>> future = executor.submit(merger);
                        uniqueSets = future.get();

                        // Clear existing items and add formatted entries with total count
                        listView.getItems().clear();
                        for (Map.Entry<String, List<FileFreq>> entry : uniqueSets.entrySet()) {
                            String word = entry.getKey();
                            List<FileFreq> fileFreqList = entry.getValue();

                            // Calculate total frequency
                            int totalFreq = fileFreqList.stream().mapToInt(FileFreq::getFreq).sum();

                            // Create frequencies string sorted in descending order
                            String frequencies = fileFreqList.stream()
                                    .mapToInt(FileFreq::getFreq)
                                    .boxed()
                                    .sorted((a, b) -> Integer.compare(b, a))
                                    .map(String::valueOf)
                                    .reduce((a, b) -> a + ", " + b)
                                    .orElse("");

                            // Format: "word [total] (freq1, freq2, ...)"
                            String displayText = String.format("%s [%d] (%s)", word, totalFreq, frequencies);
                            listView.getItems().add(displayText);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        executor.shutdown();
                    }
                    return null;
                }
            };
            processTask.setOnSucceeded(e -> {
                Launcher.primaryStage.getScene().setRoot(bgRoot);
            });
            Thread thread = new Thread(processTask);
            thread.setDaemon(true);
            thread.start();
        });
        listView.setOnMouseClicked(event -> {
            String selectedItem = (String) listView.getSelectionModel().getSelectedItem();
            if (selectedItem == null) return;

            // Extract the word from the display format "word [total] (freq1, freq2, ...)"
            String word = selectedItem;
            if (selectedItem.contains(" [")) {
                word = selectedItem.substring(0, selectedItem.indexOf(" ["));
            }

            List<FileFreq> listOfLinks = uniqueSets.get(word);
            if (listOfLinks == null) return;

            listOfLinks.sort((e1, e2) -> Integer.compare(e2.getFreq(), e1.getFreq()));
            ListView<FileFreq> popupListView = new ListView<>();
            LinkedHashMap<FileFreq, String> lookupTable = new LinkedHashMap<>();
            for (int i = 0; i < listOfLinks.size(); i++) {
                lookupTable.put(listOfLinks.get(i), listOfLinks.get(i).getPath());
                popupListView.getItems().add(listOfLinks.get(i));
            }
            popupListView.setPrefWidth(300);
            popupListView.setPrefHeight(Math.min(listOfLinks.size() * 40, 200));

            Popup popup = new Popup();
            popup.setAutoHide(true);
            popup.setHideOnEscape(true);

            popupListView.setOnMouseClicked(innerEvent -> {
                FileFreq selectedFileFreq = popupListView.getSelectionModel().getSelectedItem();
                if (selectedFileFreq != null) {
                    Launcher.hs.showDocument("file:///" + selectedFileFreq.getPath());
                    popup.hide();
                }
                innerEvent.consume();
            });

            popupListView.setOnKeyPressed(keyEvent -> {
                if (keyEvent.getCode().equals(KeyCode.ESCAPE)) {
                    popup.hide();
                    keyEvent.consume();
                }
            });

            // Ensure popup can receive focus for key events
            popupListView.setFocusTraversable(true);
            popup.getContent().add(popupListView);

            // Position popup relative to the mouse click
            popup.show(Launcher.primaryStage, event.getScreenX(), event.getScreenY());

            // Request focus for the popup to receive key events
            popupListView.requestFocus();
        });
        closeMenuItem.setOnAction(e -> Platform.exit());
        deleteMenuItem.setOnAction(e -> {
            inputListView.getItems().clear();
            listView.getItems().clear();
        });
    }
}