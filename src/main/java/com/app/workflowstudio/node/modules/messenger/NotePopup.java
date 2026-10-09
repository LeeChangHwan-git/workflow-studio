package com.app.workflowstudio.node.modules.messenger;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 시연용 쪽지 창 — 화면 오른쪽 아래(작업 표시줄 위)에 메신저 쪽지처럼 떠오른다. 여러 개면 위로 쌓임.
 * 서버가 화면 없는 환경(테스트 등)에서 돌면 아무것도 하지 않는다.
 */
final class NotePopup {

    private static final int W = 380, H = 210, GAP = 10, MAX_OPEN = 5;
    private static final Color HEAD = new Color(0x3B, 0x4C, 0xCA);
    private static final Color BG = new Color(0xFF, 0xFF, 0xFF);
    private static final Color SUB = new Color(0x6B, 0x72, 0x80);
    private static final String FONT = "Malgun Gothic";
    private static final String SYMBOL = "Segoe UI Symbol";
    private static final List<JWindow> open = new ArrayList<>();

    private NotePopup() {}

    static boolean available() {
        return !GraphicsEnvironment.isHeadless();
    }

    static void show(String sender, String message, String time) {
        if (!available()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                create(sender, message, time);
                sound();
            } catch (Exception ignore) {
                // 화면 표시는 부가 기능 — 실패해도 쪽지 접수는 성공으로
            }
        });
    }

    private static void create(String sender, String message, String time) {
        while (open.size() >= MAX_OPEN) close(open.get(0));

        JWindow w = new JWindow();
        w.setAlwaysOnTop(true);
        w.setSize(W, H);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(BorderFactory.createLineBorder(new Color(0xD0, 0xD4, 0xDC)));

        // 머리: "쪽지가 도착했습니다" + 시각
        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(HEAD);
        head.setBorder(new EmptyBorder(8, 8, 8, 12));
        // 아이콘 글자(✉ ✕)는 한글 글꼴에 없어 네모로 보임 → 윈도우 기호 글꼴로
        JPanel titleBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        titleBox.setOpaque(false);
        JLabel icon = new JLabel("✉");
        icon.setForeground(Color.WHITE);
        icon.setFont(new Font(SYMBOL, Font.PLAIN, 15));
        JLabel title = new JLabel("쪽지가 도착했습니다");
        title.setForeground(Color.WHITE);
        title.setFont(new Font(FONT, Font.BOLD, 13));
        titleBox.add(icon);
        titleBox.add(title);
        JLabel x = new JLabel("✕");
        x.setForeground(new Color(0xDD, 0xE1, 0xFF));
        x.setFont(new Font(SYMBOL, Font.PLAIN, 13));
        x.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        x.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { close(w); }
        });
        head.add(titleBox, BorderLayout.WEST);
        head.add(x, BorderLayout.EAST);

        // 본문: 보낸 사람 / 내용 (나에게 온 쪽지만 뜨므로 받는 사람은 생략)
        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setBackground(BG);
        body.setBorder(new EmptyBorder(10, 14, 6, 14));
        JLabel meta = new JLabel("<html>보낸 사람 <b>" + esc(sender) + "</b>"
                + " <span style='color:#9CA3AF'>· " + esc(time) + "</span></html>");
        meta.setFont(new Font(FONT, Font.PLAIN, 12));
        meta.setForeground(SUB);
        JTextArea text = new JTextArea(message);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setEditable(false);
        text.setFont(new Font(FONT, Font.PLAIN, 13));
        text.setForeground(new Color(0x11, 0x18, 0x27));
        text.setBackground(BG);
        text.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(null);
        body.add(meta, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);

        // 아래: 확인 버튼
        JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 6));
        foot.setBackground(new Color(0xF5, 0xF6, 0xF8));
        JButton ok = new JButton("확인");
        ok.setFont(new Font(FONT, Font.BOLD, 12));
        ok.setFocusPainted(false);
        ok.setBackground(HEAD);
        ok.setForeground(Color.WHITE);
        ok.setBorder(new EmptyBorder(5, 18, 5, 18));
        ok.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ok.addActionListener(e -> close(w));
        foot.add(ok);

        root.add(head, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        root.add(foot, BorderLayout.SOUTH);
        w.setContentPane(root);
        try {
            w.setShape(new RoundRectangle2D.Double(0, 0, W, H, 14, 14));
        } catch (Exception ignore) {
        }

        open.add(w);
        layout();
        fadeIn(w);
    }

    /** 오른쪽 아래부터 위로 쌓기 (작업 표시줄 제외 영역) */
    private static void layout() {
        Rectangle area = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int y = area.y + area.height - GAP;
        for (int i = open.size() - 1; i >= 0; i--) {
            y -= H;
            open.get(i).setLocation(area.x + area.width - W - GAP, y);
            y -= GAP;
        }
    }

    private static void fadeIn(JWindow w) {
        boolean translucent = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT);
        if (!translucent) {
            w.setVisible(true);
            return;
        }
        w.setOpacity(0f);
        w.setVisible(true);
        Timer t = new Timer(15, null);
        t.addActionListener(e -> {
            float o = Math.min(1f, w.getOpacity() + 0.08f);
            w.setOpacity(o);
            if (o >= 1f) t.stop();
        });
        t.start();
    }

    private static void close(JWindow w) {
        open.remove(w);
        w.dispose();
        layout();
    }

    /** 윈도우 메신저 알림음 (없으면 기본 '띵') */
    private static void sound() {
        File f = new File(System.getenv().getOrDefault("WINDIR", "C:\\Windows"), "Media\\Windows Notify Messaging.wav");
        try {
            if (!f.exists()) throw new IllegalStateException();
            Clip clip = AudioSystem.getClip();
            clip.open(AudioSystem.getAudioInputStream(f));
            clip.addLineListener(ev -> {
                if (ev.getType() == javax.sound.sampled.LineEvent.Type.STOP) clip.close();
            });
            clip.start();
        } catch (Exception e) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
