package com.app.workflowstudio.node.modules.messenger;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * 시연용 쪽지 창 — 화면 오른쪽 아래(작업 표시줄 위)에 메신저 쪽지처럼 떠오른다. 여러 개면 위로 쌓임.
 * <ul>
 *   <li>크기 조절: 창 가장자리·모서리 또는 오른쪽 아래 ◢ 를 끌기 — 바꾼 크기는 기억해 다음 쪽지 창에도 씀 (앱을 다시 켜도 유지)</li>
 *   <li>이동: 파란 머리를 끌기. 옮기거나 크기를 바꾼 창은 자동 쌓기에서 빠지고, 머리를 더블클릭하면 다시 오른쪽 아래로</li>
 * </ul>
 * 서버가 화면 없는 환경(테스트 등)에서 돌면 아무것도 하지 않는다.
 */
final class NotePopup {

    private static final int DEFAULT_W = 380, DEFAULT_H = 210, MIN_W = 300, MIN_H = 150, GAP = 10, MAX_OPEN = 5;
    /** 가장자리에서 이 픽셀 안이면 크기 조절 */
    private static final int EDGE = 6;
    private static final Color HEAD = new Color(0x3B, 0x4C, 0xCA);
    private static final Color BG = new Color(0xFF, 0xFF, 0xFF);
    private static final Color SUB = new Color(0x6B, 0x72, 0x80);
    private static final Color LINE = new Color(0xD0, 0xD4, 0xDC);
    private static final String FONT = "Malgun Gothic";
    private static final String SYMBOL = "Segoe UI Symbol";
    private static final List<JWindow> open = new ArrayList<>();
    /** 사용자가 옮기거나 크기를 바꾼 창 — 자동 쌓기에서 제외 */
    private static final Set<JWindow> pinned = new HashSet<>();
    /** 창마다 등록한 가장자리 크기 조절 리스너 — 닫을 때 해제 */
    private static final java.util.Map<JWindow, java.awt.event.AWTEventListener> resizers = new java.util.HashMap<>();
    private static final Preferences PREFS = Preferences.userNodeForPackage(NotePopup.class);

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

    /** 마지막으로 쓴 크기 (화면보다 크면 줄임) */
    private static Dimension savedSize() {
        Rectangle area = screenArea();
        int w = PREFS.getInt("w", DEFAULT_W), h = PREFS.getInt("h", DEFAULT_H);
        return new Dimension(Math.max(MIN_W, Math.min(w, area.width - 2 * GAP)), Math.max(MIN_H, Math.min(h, area.height - 2 * GAP)));
    }

    private static void saveSize(Dimension d) {
        PREFS.putInt("w", d.width);
        PREFS.putInt("h", d.height);
    }

    private static Rectangle screenArea() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    }

    private static void create(String sender, String message, String time) {
        while (open.size() >= MAX_OPEN) close(open.get(0));

        JWindow w = new JWindow();
        w.setAlwaysOnTop(true);
        w.setSize(savedSize());

        // 바깥 테두리(EDGE 픽셀) = 크기 조절 손잡이 영역, 안쪽 = 내용
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(LINE);
        outer.setBorder(new EmptyBorder(1, 1, 1, 1));
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        outer.add(root);

        // 머리: "쪽지가 도착했습니다" + ✕ — 끌어서 이동, 더블클릭이면 제자리(오른쪽 아래)로
        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(HEAD);
        head.setBorder(new EmptyBorder(8, 8, 8, 12));
        head.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
        head.setToolTipText("끌어서 옮기기 · 더블클릭: 오른쪽 아래로 되돌리기");
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
        MouseAdapter mover = new MouseAdapter() {
            Point grab;
            @Override public void mousePressed(MouseEvent e) { grab = e.getPoint(); }
            @Override public void mouseDragged(MouseEvent e) {
                if (grab == null) return;
                Point p = e.getLocationOnScreen();
                w.setLocation(p.x - grab.x - head.getX() - root.getX(), p.y - grab.y - head.getY() - root.getY());
                pinned.add(w);
            }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    pinned.remove(w);
                    layout();
                }
            }
        };
        head.addMouseListener(mover);
        head.addMouseMotionListener(mover);
        titleBox.addMouseListener(mover);
        titleBox.addMouseMotionListener(mover);

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

        // 아래: 크기 조절 안내 + 확인 버튼 + ◢ 손잡이
        JPanel foot = new JPanel(new BorderLayout());
        foot.setBackground(new Color(0xF5, 0xF6, 0xF8));
        foot.setBorder(new EmptyBorder(6, 12, 2, 2));
        JLabel hint = new JLabel("가장자리를 끌어 크기 조절");
        hint.setFont(new Font(FONT, Font.PLAIN, 11));
        hint.setForeground(new Color(0xA0, 0xA6, 0xB0));
        JButton ok = new JButton("확인");
        ok.setFont(new Font(FONT, Font.BOLD, 12));
        ok.setFocusPainted(false);
        ok.setBackground(HEAD);
        ok.setForeground(Color.WHITE);
        ok.setBorder(new EmptyBorder(5, 18, 5, 18));
        ok.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        ok.addActionListener(e -> close(w));
        JLabel grip = new JLabel("◢");
        grip.setFont(new Font(SYMBOL, Font.PLAIN, 12));
        grip.setForeground(new Color(0xB0, 0xB6, 0xC0));
        grip.setVerticalAlignment(SwingConstants.BOTTOM);
        grip.setBorder(new EmptyBorder(0, 6, 0, 1));
        grip.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
        grip.setToolTipText("끌어서 크기 조절");
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(ok);
        right.add(grip);
        foot.add(hint, BorderLayout.WEST);
        foot.add(right, BorderLayout.EAST);

        root.add(head, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        root.add(foot, BorderLayout.SOUTH);
        w.setContentPane(outer);

        // 크기 조절: 바깥 테두리/모서리(창 어디서든 가장자리 EDGE 픽셀) + ◢ 손잡이
        resizers.put(w, installResize(w, outer, EDGE));
        installGrip(w, grip);
        w.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { round(w); }
        });
        round(w);

        open.add(w);
        layout();
        fadeIn(w);
    }

    /** 둥근 모서리 (크기가 바뀔 때마다 다시) */
    private static void round(JWindow w) {
        try {
            w.setShape(new RoundRectangle2D.Double(0, 0, w.getWidth(), w.getHeight(), 14, 14));
        } catch (Exception ignore) {
            // 모양 창을 지원하지 않으면 네모로
        }
    }

    /**
     * 창 가장자리 크기 조절 — 전역 마우스 이벤트로 창 안 어느 컴포넌트 위든 가장자리 EDGE 픽셀이면 커서를 바꾸고 끌면 크기 변경.
     * (자식 컴포넌트가 이벤트를 먹어도 동작하도록 AWTEventListener 사용)
     */
    private static java.awt.event.AWTEventListener installResize(JWindow w, JComponent outer, int edge) {
        final int[] dir = {0};          // 비트: 1=왼 2=오른 4=위 8=아래
        final Rectangle[] start = {null};
        final Point[] press = {null};
        java.awt.event.AWTEventListener listener = ev -> {
            if (!(ev instanceof MouseEvent me) || !w.isDisplayable()) return;
            if (SwingUtilities.getWindowAncestor(me.getComponent()) != w && me.getComponent() != w) return;
            Point p = SwingUtilities.convertPoint(me.getComponent(), me.getPoint(), w.getRootPane());
            switch (me.getID()) {
                case MouseEvent.MOUSE_MOVED -> {
                    int d = edgeDir(p, w.getWidth(), w.getHeight(), edge);
                    if (d != 0) outer.getRootPane().setCursor(Cursor.getPredefinedCursor(cursorFor(d)));
                    else outer.getRootPane().setCursor(Cursor.getDefaultCursor());
                }
                case MouseEvent.MOUSE_PRESSED -> {
                    dir[0] = edgeDir(p, w.getWidth(), w.getHeight(), edge);
                    if (dir[0] != 0) {
                        start[0] = w.getBounds();
                        press[0] = me.getLocationOnScreen();
                        me.consume();
                    }
                }
                case MouseEvent.MOUSE_DRAGGED -> {
                    if (dir[0] == 0 || start[0] == null) return;
                    Point now = me.getLocationOnScreen();
                    resizeTo(w, start[0], dir[0], now.x - press[0].x, now.y - press[0].y);
                    me.consume();
                }
                case MouseEvent.MOUSE_RELEASED -> {
                    if (dir[0] != 0) saveSize(w.getSize());
                    dir[0] = 0;
                    start[0] = null;
                }
                default -> { }
            }
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        return listener;
    }

    /** ◢ 손잡이 — 오른쪽 아래 모서리 끌기 */
    private static void installGrip(JWindow w, JComponent grip) {
        MouseAdapter a = new MouseAdapter() {
            Rectangle start;
            Point press;
            @Override public void mousePressed(MouseEvent e) { start = w.getBounds(); press = e.getLocationOnScreen(); }
            @Override public void mouseDragged(MouseEvent e) {
                if (start == null) return;
                Point now = e.getLocationOnScreen();
                resizeTo(w, start, 2 | 8, now.x - press.x, now.y - press.y);
            }
            @Override public void mouseReleased(MouseEvent e) { start = null; saveSize(w.getSize()); }
        };
        grip.addMouseListener(a);
        grip.addMouseMotionListener(a);
    }

    private static void resizeTo(JWindow w, Rectangle s, int d, int dx, int dy) {
        Rectangle area = screenArea();
        int x = s.x, y = s.y, width = s.width, height = s.height;
        if ((d & 2) != 0) width = s.width + dx;
        if ((d & 8) != 0) height = s.height + dy;
        if ((d & 1) != 0) width = s.width - dx;
        if ((d & 4) != 0) height = s.height - dy;
        width = Math.max(MIN_W, Math.min(width, area.width));
        height = Math.max(MIN_H, Math.min(height, area.height));
        if ((d & 1) != 0) x = s.x + s.width - width;    // 왼쪽을 끌면 오른쪽 끝 고정
        if ((d & 4) != 0) y = s.y + s.height - height;  // 위를 끌면 아래 끝 고정
        w.setBounds(x, y, width, height);
        w.revalidate();
        pinned.add(w);
    }

    private static int edgeDir(Point p, int width, int height, int edge) {
        int d = 0;
        if (p.x <= edge) d |= 1;
        if (p.x >= width - edge) d |= 2;
        if (p.y <= edge) d |= 4;
        if (p.y >= height - edge) d |= 8;
        return d;
    }

    private static int cursorFor(int d) {
        return switch (d) {
            case 1 -> Cursor.W_RESIZE_CURSOR;
            case 2 -> Cursor.E_RESIZE_CURSOR;
            case 4 -> Cursor.N_RESIZE_CURSOR;
            case 8 -> Cursor.S_RESIZE_CURSOR;
            case 1 | 4 -> Cursor.NW_RESIZE_CURSOR;
            case 2 | 4 -> Cursor.NE_RESIZE_CURSOR;
            case 1 | 8 -> Cursor.SW_RESIZE_CURSOR;
            default -> Cursor.SE_RESIZE_CURSOR;
        };
    }

    /** 오른쪽 아래부터 위로 쌓기 (작업 표시줄 제외 영역) — 옮기거나 크기를 바꾼 창은 그대로 둠 */
    private static void layout() {
        Rectangle area = screenArea();
        int y = area.y + area.height - GAP;
        for (int i = open.size() - 1; i >= 0; i--) {
            JWindow w = open.get(i);
            if (pinned.contains(w)) continue;
            y -= w.getHeight();
            w.setLocation(area.x + area.width - w.getWidth() - GAP, Math.max(area.y, y));
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
        pinned.remove(w);
        var l = resizers.remove(w);
        if (l != null) Toolkit.getDefaultToolkit().removeAWTEventListener(l);
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
