package ma.aui.csc3374.client.ui;

import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.shape.Circle;
import ma.aui.csc3374.client.ChatClient;
import ma.aui.csc3374.client.ClientConfig;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

final class ConnectedView extends GridPane {
    private static final DateTimeFormatter MESSAGE_TIME =
            DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault());
    private final ListView<ChatClient.ChatMessage> messages = new ListView<>();
    private final ListView<String> participants = new ListView<>();
    private final TextArea composer = new TextArea();
    private final Button send = button("SEND", "send", "send-button");
    private final Button refresh = button("allUsers", "refresh-users", "chat-button");
    private final Button leave = button("LEAVE", "leave", "chat-button");
    private final Label notice = label("", "chat-notice");
    private final boolean readOnly;

    ConnectedView(ClientConfig config, ChatClient.Identity identity, Consumer<String> submit,
                  Runnable requestUsers, Runnable disconnect) {
        readOnly = identity.readOnly();
        getStyleClass().add("connected");
        ColumnConstraints rail = new ColumnConstraints(200, 224, 248, Priority.NEVER, HPos.LEFT, true);
        ColumnConstraints conversation = new ColumnConstraints();
        conversation.setHgrow(Priority.ALWAYS);
        getColumnConstraints().addAll(rail, conversation);

        participants.setId("participants");
        participants.setAccessibleText("Active users snapshot");
        participants.setPlaceholder(label("Request the active user list.", "empty-participants"));
        refresh.setAccessibleText("allUsers, request active users");
        refresh.setOnAction(event -> requestUsers.run());

        GridPane sidebar = new GridPane();
        sidebar.getStyleClass().add("chat-sidebar");
        ColumnConstraints sidebarColumn = new ColumnConstraints();
        sidebarColumn.setHgrow(Priority.ALWAYS);
        sidebar.getColumnConstraints().add(sidebarColumn);
        Label endpoint = label(config.host() + ":" + config.port(), "chat-caption");
        endpoint.setWrapText(true);
        sidebar.addColumn(0, label("RELAY", "chat-brand"), endpoint, label("Participants", "panel-title"),
                participants, refresh, label("List updates on request", "chat-caption"));
        setVgrow(participants, Priority.ALWAYS);
        refresh.setMaxWidth(Double.MAX_VALUE);

        Circle dot = new Circle(4);
        dot.getStyleClass().add("online-dot");
        Label status = label("Online", "chat-online");
        status.setId("connection-status");
        status.setGraphic(dot);
        Label username = label("Connected as " + identity.username(), "chat-caption");
        username.setId("connected-identity");
        username.setTooltip(new Tooltip(username.getText()));
        Label mode = label(readOnly ? "READ-ONLY MODE · guest access" : "Named session · connected", "chat-caption");
        mode.setId("connection-mode");

        GridPane header = new GridPane();
        header.getStyleClass().add("chat-header");
        Label room = label("Group Chat", "chat-title");
        header.addColumn(0, room, username);
        header.add(status, 1, 0);
        header.add(mode, 1, 1);
        header.add(leave, 2, 0, 1, 2);
        setHgrow(room, Priority.ALWAYS);
        leave.setOnAction(event -> disconnect.run());

        messages.setId("received-messages");
        messages.setAccessibleText("Group conversation");
        messages.setPlaceholder(label("No messages yet.", "chat-caption"));
        messages.setCellFactory(list -> new MessageCell(identity.username(), list));
        notice.setId("chat-notice");
        notice.setWrapText(true);
        showNotice("");

        composer.setId("composer");
        composer.setAccessibleText("Message");
        composer.setWrapText(true);
        composer.setPrefRowCount(2);
        composer.setPromptText(readOnly ? "Guest access is read-only." : "Write a message…");
        composer.setDisable(readOnly);
        send.disableProperty().bind(composer.disabledProperty().or(composer.editableProperty().not())
                .or(Bindings.createBooleanBinding(
                        () -> composer.getText().isBlank(), composer.textProperty())));
        send.setOnAction(event -> submit.accept(composer.getText()));
        composer.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                event.consume();
                send.fire();
            }
        });

        GridPane input = new GridPane();
        input.getStyleClass().add("chat-input");
        input.addRow(0, composer, send);
        setHgrow(composer, Priority.ALWAYS);
        send.setMaxHeight(Double.MAX_VALUE);

        GridPane conversationPane = new GridPane();
        conversationPane.getStyleClass().add("conversation");
        conversationPane.addColumn(0, header, messages, notice, input);
        setHgrow(messages, Priority.ALWAYS);
        setVgrow(messages, Priority.ALWAYS);
        addRow(0, sidebar, conversationPane);
        setVgrow(conversationPane, Priority.ALWAYS);
    }

    void receive(ChatClient.Event event) {
        switch (event) {
            case ChatClient.ChatMessage message -> {
                messages.getItems().add(message);
                messages.scrollTo(messages.getItems().size() - 1);
            }
            case ChatClient.UserList users -> participants.getItems().setAll(users.usernames());
            case ChatClient.ServerError error -> showNotice(error.text());
        }
    }

    void setSending(boolean sending) {
        composer.setEditable(!sending && !readOnly);
        refresh.setDisable(sending);
        leave.setDisable(sending);
    }

    void finishSending(boolean clearComposer, String error) {
        if (clearComposer) {
            composer.clear();
            focusComposer();
        }
        if (!error.isEmpty()) {
            showNotice(error);
            focusComposer();
        }
        setSending(false);
    }

    void showNotice(String message) {
        notice.setText(message);
        notice.setManaged(!message.isEmpty());
        notice.setVisible(!message.isEmpty());
    }

    void focusComposer() {
        if (readOnly) {
            refresh.requestFocus();
        } else {
            composer.requestFocus();
        }
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }

    private static Button button(String text, String id, String style) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().add(style);
        return button;
    }

    private static final class MessageCell extends ListCell<ChatClient.ChatMessage> {
        private final Label detail = label("", "message-detail");
        private final Label body = label("", "message-body");
        private final GridPane bubble = new GridPane();
        private final String username;

        private MessageCell(String username, ListView<ChatClient.ChatMessage> list) {
            this.username = username;
            var contentWidth = list.widthProperty().multiply(0.66).subtract(26);
            detail.maxWidthProperty().bind(contentWidth);
            body.setWrapText(true);
            body.setMinWidth(0);
            body.setMinHeight(USE_PREF_SIZE);
            body.maxWidthProperty().bind(contentWidth);
            bubble.setMaxWidth(USE_PREF_SIZE);
            bubble.getStyleClass().add("message-bubble");
            bubble.addColumn(0, detail, body);
            setText(null);
        }

        @Override
        protected void updateItem(ChatClient.ChatMessage message, boolean empty) {
            super.updateItem(message, empty);
            if (empty || message == null) {
                setGraphic(null);
                return;
            }
            boolean own = message.sender().equals(username);
            setAlignment(own ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            bubble.pseudoClassStateChanged(PseudoClass.getPseudoClass("own"), own);
            detail.setText(message.sender() + "  ·  " + MESSAGE_TIME.format(message.timestamp()));
            detail.setTooltip(new Tooltip(message.timestamp().toString()));
            body.setText(message.text());
            setGraphic(bubble);
        }
    }
}
