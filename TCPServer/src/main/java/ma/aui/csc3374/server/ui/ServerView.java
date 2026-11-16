package ma.aui.csc3374.server.ui;

import javafx.css.PseudoClass;
import javafx.geometry.HPos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.shape.Circle;
import ma.aui.csc3374.server.ChatServer;
import ma.aui.csc3374.server.ServerConfig;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

final class ServerView extends GridPane {
    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final List<String> USER_COLORS =
            List.of("#503972", "#395875", "#71415b", "#326259", "#754e39", "#66622f", "#484d87", "#71404e");

    private final Label status = label("", "server-status");
    private final Circle statusDot = new Circle(4);
    private final Button stop = new Button("Stop Server");
    private final ListView<String> users = new ListView<>();
    private final ListView<ChatServer.Activity> activity = new ListView<>();
    private final Map<String, String> userColors = new HashMap<>();

    ServerView(ServerConfig settings, Runnable shutdown) {
        getStyleClass().add("server");
        status.setId("server-status");
        status.setGraphic(statusDot);
        statusDot.getStyleClass().add("status-dot");
        stop.setId("stop-server");
        stop.getStyleClass().add("stop-server");
        stop.setOnAction(event -> shutdown.run());
        setStatus("Starting…", false);

        Label endpoint = label(settings.host() + ":" + settings.port(), "server-endpoint");
        endpoint.setId("server-endpoint");
        Label title = label("RELAY Server", "server-title");
        GridPane header = new GridPane();
        header.getStyleClass().add("server-header");
        header.addColumn(0, title, endpoint);
        header.add(status, 1, 0, 1, 2);
        header.add(stop, 2, 0, 1, 2);
        setHgrow(title, Priority.ALWAYS);

        activity.setId("server-activity");
        activity.setAccessibleText("Server activity");
        activity.setPlaceholder(label("Waiting for server activity.", "server-muted"));
        activity.setCellFactory(list -> new ActivityCell(list));
        users.setId("server-users");
        users.setAccessibleText("Connected users");
        users.setPlaceholder(label("No connected users.", "server-muted"));
        users.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                setText(empty ? null : name);
                setTooltip(empty ? null : new Tooltip(name));
                setStyle(empty ? "" : "-fx-background-color: " + userColors.get(name) + ";");
            }
        });

        ColumnConstraints logColumn = new ColumnConstraints();
        logColumn.setHgrow(Priority.ALWAYS);
        ColumnConstraints userColumn = new ColumnConstraints(210, 244, 280, Priority.NEVER, HPos.LEFT, true);
        getColumnConstraints().addAll(logColumn, userColumn);
        add(header, 0, 0, 2, 1);
        GridPane logPanel = panel("Activity Log", activity);
        GridPane userPanel = panel("Connected Users", users);
        addRow(1, logPanel, userPanel);
        setVgrow(logPanel, Priority.ALWAYS);
    }

    void setStatus(String text, boolean online) {
        status.setText(text);
        statusDot.pseudoClassStateChanged(PseudoClass.getPseudoClass("online"), online);
        stop.setDisable(!online);
    }

    void append(ChatServer.Activity entry) {
        if (activity.getItems().size() >= 500) {
            activity.getItems().removeFirst();
        }
        activity.getItems().add(entry);
        activity.scrollTo(activity.getItems().size() - 1);
    }

    void setUsers(List<String> names) {
        if (users.getItems().equals(names)) {
            return;
        }
        userColors.keySet().retainAll(names);
        for (String name : names) {
            userColors.computeIfAbsent(name, ignored -> randomColor());
        }
        users.getItems().setAll(names);
    }

    private String randomColor() {
        var available = new ArrayList<>(USER_COLORS);
        available.removeAll(userColors.values());
        var random = ThreadLocalRandom.current();
        if (!available.isEmpty()) {
            return available.get(random.nextInt(available.size()));
        }
        String color;
        do {
            color = "hsb(" + random.nextDouble(360) + ", 42%, 41%)";
        } while (userColors.containsValue(color));
        return color;
    }

    private static GridPane panel(String heading, ListView<?> list) {
        GridPane pane = new GridPane();
        pane.getStyleClass().add("server-panel");
        pane.addColumn(0, label(heading, "server-panel-title"), list);
        setHgrow(list, Priority.ALWAYS);
        setVgrow(list, Priority.ALWAYS);
        return pane;
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    private static final class ActivityCell extends ListCell<ChatServer.Activity> {
        private final Label time = label("", "server-log-time");
        private final Label description = label("", "server-log-message");
        private final GridPane row = new GridPane();

        ActivityCell(ListView<ChatServer.Activity> list) {
            row.getStyleClass().add("server-log-row");
            row.addRow(0, time, description);
            row.prefWidthProperty().bind(list.widthProperty().subtract(36));
            description.setWrapText(true);
            description.setMinWidth(0);
            setHgrow(description, Priority.ALWAYS);
        }

        @Override
        protected void updateItem(ChatServer.Activity entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
            } else {
                time.setText(LOG_TIME.format(entry.timestamp()));
                description.setText(entry.message());
                setGraphic(row);
            }
        }
    }
}
