package com.app.memobookmark;

import javafx.application.Application;

public class Launcher {
    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "false");
        Application.launch(DesktopApp.class, args);
    }
}
