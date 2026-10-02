package com.app.workflowstudio.controller;

import com.app.workflowstudio.node.modules.filewrite.FileWriteModule;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * '📁 찾아보기' — 서버가 도는 PC(=사용자 PC)에 폴더/파일 선택 창을 띄우고 고른 경로를 돌려준다.
 * 창이 브라우저 뒤에 숨지 않도록 항상 위에 있는 보이지 않는 부모 창을 사용. 한 번에 하나만 열림.
 * 요청 {initial?} → 응답 {path} | {cancelled:true} | {error}
 */
@RestController
public class FileDialogController {

    private static volatile boolean lafSet = false;

    /** 폴더 선택 (파일 쓰기 노드의 저장 폴더) */
    @PostMapping("/api/dialog/folder")
    public ResponseEntity<Map<String, Object>> pickFolder(@RequestBody(required = false) Map<String, String> req) {
        return show(req, "폴더 선택", "이 폴더 선택", JFileChooser.DIRECTORIES_ONLY);
    }

    /** 파일 선택 (지시문 노드의 지시문 파일 — txt/md 우선, 모든 파일도 선택 가능) */
    @PostMapping("/api/dialog/file")
    public ResponseEntity<Map<String, Object>> pickFile(@RequestBody(required = false) Map<String, String> req) {
        return show(req, "파일 선택", "열기", JFileChooser.FILES_ONLY);
    }

    private synchronized ResponseEntity<Map<String, Object>> show(Map<String, String> req, String title,
                                                                  String approveText, int mode) {
        if (GraphicsEnvironment.isHeadless()) {
            return ResponseEntity.badRequest().body(Map.of("error", "화면이 없는 환경이라 선택 창을 띄울 수 없습니다"));
        }
        String initial = req != null ? req.get("initial") : null;
        AtomicReference<String> chosen = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();

        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame owner = null;
                try {
                    useSystemLookAndFeel();
                    owner = new JFrame();
                    owner.setUndecorated(true);
                    owner.setAlwaysOnTop(true);
                    owner.setSize(0, 0);
                    owner.setLocationRelativeTo(null);
                    owner.setVisible(true);
                    owner.toFront();

                    JFileChooser chooser = new JFileChooser();
                    chooser.setDialogTitle(title);
                    chooser.setFileSelectionMode(mode);
                    if (mode == JFileChooser.FILES_ONLY) {
                        chooser.addChoosableFileFilter(new FileNameExtensionFilter("텍스트 (*.txt, *.md)", "txt", "md"));
                        chooser.setFileFilter(chooser.getChoosableFileFilters()[1]);
                    } else {
                        chooser.setAcceptAllFileFilterUsed(false);
                    }
                    File start = startPath(initial);
                    if (start.isFile()) chooser.setSelectedFile(start);
                    else chooser.setCurrentDirectory(start);

                    if (chooser.showDialog(owner, approveText) == JFileChooser.APPROVE_OPTION) {
                        chosen.set(chooser.getSelectedFile().getAbsolutePath());
                    }
                } catch (Exception e) {
                    failure.set(e);
                } finally {
                    if (owner != null) owner.dispose();
                }
            });
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "선택 창 오류: " + e.getMessage()));
        }

        if (failure.get() != null) {
            return ResponseEntity.internalServerError().body(Map.of("error", "선택 창 오류: " + failure.get().getMessage()));
        }
        return chosen.get() == null
                ? ResponseEntity.ok(Map.of("cancelled", true))
                : ResponseEntity.ok(Map.of("path", chosen.get()));
    }

    /** 입력칸의 현재 값(%USERPROFILE% 등 포함)을 시작 위치로 — 해석 실패 시 홈 폴더 */
    private static File startPath(String initial) {
        if (initial != null && !initial.isBlank()) {
            try {
                File f = FileWriteModule.expandPath(initial).toFile();
                if (f.isFile()) return f;
                while (f != null && !f.isDirectory()) f = f.getParentFile();
                if (f != null) return f;
            } catch (Exception ignore) {}
        }
        return new File(System.getProperty("user.home"));
    }

    private static void useSystemLookAndFeel() {
        if (lafSet) return;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignore) {}
        lafSet = true;
    }
}
