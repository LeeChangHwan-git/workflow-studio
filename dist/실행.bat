@echo off
cd /d %~dp0
start "" javaw ^
  --module-path libs ^
  --add-modules javafx.base,javafx.controls,javafx.graphics,javafx.web,javafx.fxml,javafx.media ^
  -jar memo-bookmark-app-1.0.0.jar
