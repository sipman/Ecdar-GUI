package ecdar.controllers;

import com.jfoenix.controls.JFXRippler;

import ecdar.abstractions.Component;
import ecdar.abstractions.EcdarSystem;
import ecdar.presentations.CanvasPresentation;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;

import java.net.URL;
import java.util.ResourceBundle;

public class CanvasShellController implements Initializable {
    public Pane root;

    public HBox toolbar;
    public JFXRippler zoomIn;
    public JFXRippler zoomOut;
    public JFXRippler zoomToFit;
    public JFXRippler resetZoom;

    public CanvasPresentation canvasPresentation;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        canvasPresentation.getController().activeComponentProperty().addListener(((observable, oldValue, newValue) -> {
            boolean shouldZoomBeActive = newValue instanceof Component || newValue instanceof EcdarSystem;
            toolbar.setVisible(shouldZoomBeActive);
            canvasPresentation.getController().zoomHelper.setActive(shouldZoomBeActive);
        }));
    }
    @FXML
    private void zoomInClicked() {
        canvasPresentation.getController().zoomHelper.zoomIn();
    }

    @FXML
    private void zoomOutClicked() {
        canvasPresentation.getController().zoomHelper.zoomOut();
    }

    @FXML
    private void zoomToFitClicked() {
        canvasPresentation.getController().zoomHelper.zoomToFit();
    }

    @FXML
    private void resetZoomClicked() {
        canvasPresentation.getController().zoomHelper.resetZoom();
    }
}
