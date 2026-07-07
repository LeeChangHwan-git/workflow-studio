package com.app.memobookmark.controller;

import com.app.memobookmark.model.Bookmark;
import com.app.memobookmark.service.BookmarkService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.awt.Desktop;
import java.net.URI;

import java.util.List;

@Controller
@RequestMapping("/bookmarks")
public class BookmarkController {

    private final BookmarkService bookmarkService;

    public BookmarkController(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String category,
                       @RequestParam(required = false) String search,
                       Model model) {
        List<Bookmark> bookmarks;
        if (search != null && !search.isBlank()) {
            bookmarks = bookmarkService.search(search);
            model.addAttribute("search", search);
        } else if (category != null && !category.isBlank()) {
            bookmarks = bookmarkService.findByCategory(category);
            model.addAttribute("category", category);
        } else {
            bookmarks = bookmarkService.findAll();
        }
        model.addAttribute("bookmarks", bookmarks);
        return "bookmark/list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("bookmark", new Bookmark());
        return "bookmark/form";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable String id, Model model) {
        Bookmark bookmark = bookmarkService.findById(id);
        if (bookmark == null) return "redirect:/bookmarks";
        model.addAttribute("bookmark", bookmark);
        return "bookmark/form";
    }

    @PostMapping
    public String create(@ModelAttribute Bookmark bookmark) {
        bookmarkService.create(bookmark);
        return "redirect:/bookmarks";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @ModelAttribute Bookmark bookmark) {
        bookmarkService.update(id, bookmark);
        return "redirect:/bookmarks";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id) {
        bookmarkService.delete(id);
        return "redirect:/bookmarks";
    }

    @GetMapping("/{id}/open")
    public String open(@PathVariable String id) {
        Bookmark bk = bookmarkService.findById(id);
        if (bk == null) return "redirect:/bookmarks";

        String url = bk.getUrl();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        String browser = bk.getBrowser();
        try {
            if (browser == null || browser.isBlank() || "default".equals(browser)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                String exe = switch (browser) {
                    case "edge"   -> "msedge";
                    case "chrome" -> "chrome";
                    case "ie"     -> "iexplore";
                    default       -> null;
                };
                if (exe != null) {
                    boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                    if (isWindows) {
                        new ProcessBuilder("cmd", "/c", "start", exe, url).start();
                    } else {
                        new ProcessBuilder("open", "-a", exe, url).start();
                    }
                } else {
                    Desktop.getDesktop().browse(new URI(url));
                }
            }
        } catch (Exception ignored) {}

        return "redirect:/bookmarks";
    }
}
