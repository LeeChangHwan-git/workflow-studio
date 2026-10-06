package com.app.workflowstudio.node.modules.fileimport;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;

import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/** 파일 가져오기 노드 — 확장자별로 AI가 읽을 수 있는 텍스트를 뽑아낸다. */
final class FileTextExtractor {

    private FileTextExtractor() {}

    static final String UNSUPPORTED_HINT = " — 지원: 텍스트 계열(txt/md/csv/json/log 등), html, pdf, docx, xlsx, pptx";

    /** 확장자(점 없이, 소문자)에 맞게 텍스트를 뽑는다. 알려지지 않은 확장자는 텍스트로 시도하고, 바이너리로 보이면 오류 */
    static String extract(Path file, String ext, Charset charset) throws Exception {
        return switch (ext) {
            case "pdf" -> pdf(file);
            case "docx" -> docx(file);
            case "xlsx", "xlsm" -> xlsx(file);
            case "pptx" -> pptx(file);
            case "html", "htm" -> html(file);
            default -> plainText(file, charset, ext);
        };
    }

    private static String pdf(Path file) throws Exception {
        try (PDDocument doc = PDDocument.load(file.toFile())) {
            if (doc.isEncrypted()) throw new Exception("암호로 잠긴 pdf 파일은 열 수 없습니다: " + file.getFileName());
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String docx(Path file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file.toFile());
             XWPFDocument doc = new XWPFDocument(fis);
             XWPFWordExtractor ex = new XWPFWordExtractor(doc)) {
            return ex.getText();
        }
    }

    private static String xlsx(Path file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file.toFile());
             Workbook wb = WorkbookFactory.create(fis)) {
            DataFormatter fmt = new DataFormatter();
            StringBuilder sb = new StringBuilder();
            for (Sheet sheet : wb) {
                sb.append("## ").append(sheet.getSheetName()).append('\n');
                for (Row row : sheet) {
                    StringBuilder line = new StringBuilder();
                    for (Cell cell : row) {
                        if (line.length() > 0) line.append('\t');
                        line.append(fmt.formatCellValue(cell));
                    }
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private static String pptx(Path file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file.toFile());
             XMLSlideShow ppt = new XMLSlideShow(fis)) {
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (XSLFSlide slide : ppt.getSlides()) {
                sb.append("## 슬라이드 ").append(i++).append('\n');
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape ts) sb.append(ts.getText()).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private static String html(Path file) throws Exception {
        return Jsoup.parse(file.toFile(), null).text();
    }

    private static String plainText(Path file, Charset charset, String ext) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        if (looksBinary(bytes)) {
            throw new Exception("텍스트로 읽을 수 없는 파일 형식인 것 같습니다 (." + (ext.isEmpty() ? "?" : ext) + ")" + UNSUPPORTED_HINT);
        }
        int offset = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF)
                ? 3 /* UTF-8 BOM */ : 0;
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    /** 앞부분에 NUL 바이트가 있으면 텍스트가 아닌 바이너리로 본다 (git이 쓰는 것과 같은 방식) */
    private static boolean looksBinary(byte[] bytes) {
        int check = Math.min(bytes.length, 8000);
        for (int i = 0; i < check; i++) if (bytes[i] == 0) return true;
        return false;
    }
}
