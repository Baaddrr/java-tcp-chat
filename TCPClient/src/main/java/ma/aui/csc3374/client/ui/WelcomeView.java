package ma.aui.csc3374.client.ui;

import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

import java.util.Objects;
import java.util.function.Consumer;

final class WelcomeView extends StackPane {
    private final TextField username = new TextField();
    private final Button join = new Button("Join Chat");
    private final Button guest = new Button("Continue as Guest");
    private final Label error = new Label();

    WelcomeView(Consumer<String> connect) {
        getStyleClass().add("welcome");
        setPadding(new Insets(32, 24, 32, 78));

        GridPane card = new GridPane();
        card.getStyleClass().add("welcome-card");
        card.setMinSize(418, 554);
        card.setPrefSize(418, 554);
        card.setMaxSize(418, 554);
        card.setPadding(new Insets(48, 40, 12, 40));
        ColumnConstraints formColumn = new ColumnConstraints();
        formColumn.setHgrow(Priority.ALWAYS);
        formColumn.setFillWidth(true);
        card.getColumnConstraints().add(formColumn);

        Label greeting = label("Welcome to", "greeting");
        Label title = label("RELAY", "brand");
        Label description = label("Your space to connect.\nChoose a username to join\nthe conversation.", "description");
        GridPane.setMargin(description, new Insets(5, 0, 26, 0));
        Label usernameLabel = label("USERNAME", "field-label");
        usernameLabel.setLabelFor(username);
        GridPane.setMargin(usernameLabel, new Insets(0, 0, 8, 4));
        username.setId("username");
        username.setPromptText("Enter your username");
        username.setAccessibleText("Username");
        username.setMaxWidth(Double.MAX_VALUE);

        SVGPath icon = new SVGPath();
        icon.setContent("M 12 2 A 6 6 0 1 1 11.99 2 M 1 26 C 1 16 23 16 23 26 Z");
        icon.getStyleClass().add("user-icon");
        icon.setMouseTransparent(true);
        StackPane input = new StackPane(username, icon);
        StackPane.setAlignment(icon, Pos.CENTER_LEFT);
        StackPane.setMargin(icon, new Insets(0, 0, 0, 25));
        input.getStyleClass().add("username-input");
        input.setMinHeight(60);
        input.setMaxHeight(60);
        username.focusedProperty().addListener((observable, before, focused) ->
                input.pseudoClassStateChanged(PseudoClass.getPseudoClass("focused"), focused));

        join.setId("join");
        join.getStyleClass().add("join-button");
        join.setMinHeight(60);
        join.setMaxWidth(Double.MAX_VALUE);
        join.setOnAction(event -> {
            String name = username.getText().strip();
            if (name.isEmpty()) {
                showError("Enter a username or continue as guest.");
                username.requestFocus();
            } else {
                connect.accept(name);
            }
        });
        username.setOnAction(event -> join.fire());
        GridPane.setMargin(join, new Insets(24, 0, 14, 0));

        guest.setId("guest");
        guest.getStyleClass().add("guest-button");
        guest.setMaxWidth(Double.MAX_VALUE);
        guest.setOnAction(event -> connect.accept(""));
        Label caption = label("Guest access is read-only", "guest-caption");
        caption.setMaxWidth(Double.MAX_VALUE);
        caption.setAlignment(Pos.CENTER);
        error.setId("welcome-error");
        error.getStyleClass().add("inline-error");
        error.setWrapText(true);
        error.setMinHeight(36);
        error.setPrefHeight(36);
        error.setMaxWidth(Double.MAX_VALUE);
        error.setAlignment(Pos.CENTER);

        card.addColumn(0, greeting, title, description, usernameLabel, input, join, guest, caption, error);

        Image image = new Image(Objects.requireNonNull(getClass().getResource("relay-illustration.png")).toExternalForm());
        if (image.isError()) {
            throw new IllegalStateException("Cannot load RELAY illustration", image.getException());
        }
        ImageView illustration = new ImageView(image);
        illustration.setPreserveRatio(true);
        illustration.setSmooth(true);
        illustration.setMouseTransparent(true);
        StackPane art = new StackPane(illustration);
        art.setMinWidth(0);
        art.setPrefWidth(648);
        illustration.fitWidthProperty().bind(art.widthProperty());
        illustration.setFitHeight(510);

        GridPane composition = new GridPane();
        composition.setAlignment(Pos.CENTER);
        composition.setHgap(-26); // The asset's transparent margin lets the artwork sit close to the card.
        composition.setMaxWidth(1040);
        composition.add(card, 0, 0);
        composition.add(art, 1, 0);
        GridPane.setMargin(art, new Insets(0, 0, 60, 0));
        GridPane.setHgrow(art, Priority.ALWAYS);
        getChildren().addAll(decoration(), composition);
    }

    private Pane decoration() {
        Pane rings = new Pane();
        rings.setManaged(false);
        rings.setMouseTransparent(true);
        rings.prefWidthProperty().bind(widthProperty());
        rings.prefHeightProperty().bind(heightProperty());
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        rings.setClip(clip);
        for (int radius : new int[]{150, 194, 238, 282}) {
            Circle top = new Circle(-30, -62, radius);
            Circle bottom = new Circle(radius);
            bottom.centerXProperty().bind(widthProperty().add(66));
            bottom.centerYProperty().bind(heightProperty().add(40));
            top.getStyleClass().add("decorative-ring");
            bottom.getStyleClass().add("decorative-ring");
            rings.getChildren().addAll(top, bottom);
        }
        return rings;
    }

    void focusUsername() {
        username.requestFocus();
    }

    void setConnecting(boolean connecting) {
        username.setDisable(connecting);
        join.setDisable(connecting);
        guest.setDisable(connecting);
        join.setText(connecting ? "Connecting..." : "Join Chat");
        showError("");
    }

    void showError(String message) {
        error.setGraphic(null);
        error.setText(message);
    }

    void showOffline(String message) {
        Circle dot = new Circle(4);
        dot.getStyleClass().add("offline-dot");
        error.setGraphic(dot);
        error.setText(message.isEmpty() ? "Offline" : "Offline · " + message);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setMinHeight(USE_PREF_SIZE);
        return label;
    }
}
