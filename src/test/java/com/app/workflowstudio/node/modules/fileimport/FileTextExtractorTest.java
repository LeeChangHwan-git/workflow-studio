package com.app.workflowstudio.node.modules.fileimport;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 확장자별 텍스트 추출 — 실제로 pdf/docx/xlsx/pptx/html 파일을 만들어서 뽑아본다 (OS 무관, Windows 전용 가드 없음) */
class FileTextExtractorTest {

    @TempDir Path dir;

    @Test
    void 텍스트_파일은_UTF8_BOM을_제거하고_읽는다() throws Exception {
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "안녕하세요".getBytes(StandardCharsets.UTF_8);
        Path f = dir.resolve("a.txt");
        try (var out = new FileOutputStream(f.toFile())) {
            out.write(bom);
            out.write(body);
        }
        assertThat(FileTextExtractor.extract(f, "txt", StandardCharsets.UTF_8)).isEqualTo("안녕하세요");
    }

    @Test
    void EUC_KR_인코딩으로도_읽을_수_있다() throws Exception {
        Path f = dir.resolve("b.txt");
        Files.write(f, "안녕".getBytes(Charset.forName("MS949")));
        assertThat(FileTextExtractor.extract(f, "txt", Charset.forName("MS949"))).isEqualTo("안녕");
    }

    @Test
    void 알려지지_않은_확장자도_텍스트면_그대로_읽는다() throws Exception {
        Path f = dir.resolve("c.log");
        Files.writeString(f, "2026-10-06 INFO 시작됨");
        assertThat(FileTextExtractor.extract(f, "log", StandardCharsets.UTF_8)).isEqualTo("2026-10-06 INFO 시작됨");
    }

    @Test
    void 바이너리_파일은_오류를_던진다() {
        Path f = dir.resolve("d.dat");
        assertThatThrownBy(() -> {
            Files.write(f, new byte[]{1, 2, 0, 3, 4});
            FileTextExtractor.extract(f, "dat", StandardCharsets.UTF_8);
        }).hasMessageContaining("텍스트로 읽을 수 없는");
    }

    @Test
    void html_파일은_태그와_스크립트를_빼고_본문만_뽑는다() throws Exception {
        Path f = dir.resolve("e.html");
        Files.writeString(f, "<html><head><script>alert(1)</script></head>"
                + "<body><p>본문 내용입니다</p></body></html>", StandardCharsets.UTF_8);
        String text = FileTextExtractor.extract(f, "html", StandardCharsets.UTF_8);
        assertThat(text).contains("본문 내용입니다").doesNotContain("<p>").doesNotContain("alert");
    }

    @Test
    void pdf_에서_글자를_뽑는다() throws Exception {
        Path f = dir.resolve("f.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText("Hello PDF extraction test");
                cs.endText();
            }
            doc.save(f.toFile());
        }
        assertThat(FileTextExtractor.extract(f, "pdf", StandardCharsets.UTF_8)).contains("Hello PDF extraction test");
    }

    @Test
    void docx_에서_문단_텍스트를_뽑는다() throws Exception {
        Path f = dir.resolve("g.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun run = p.createRun();
            run.setText("Hello docx paragraph");
            try (var out = new FileOutputStream(f.toFile())) {
                doc.write(out);
            }
        }
        assertThat(FileTextExtractor.extract(f, "docx", StandardCharsets.UTF_8)).contains("Hello docx paragraph");
    }

    @Test
    void xlsx_에서_셀을_탭으로_구분해_뽑는다() throws Exception {
        Path f = dir.resolve("h.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("A1");
            row.createCell(1).setCellValue("B1");
            try (var out = new FileOutputStream(f.toFile())) {
                wb.write(out);
            }
        }
        String text = FileTextExtractor.extract(f, "xlsx", StandardCharsets.UTF_8);
        assertThat(text).contains("## Sheet1").contains("A1\tB1");
    }

    @Test
    void pptx_에서_슬라이드_텍스트를_뽑는다() throws Exception {
        Path f = dir.resolve("i.pptx");
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            XSLFSlide slide = ppt.createSlide();
            XSLFTextBox box = slide.createTextBox();
            box.setText("Hello pptx slide");
            try (var out = new FileOutputStream(f.toFile())) {
                ppt.write(out);
            }
        }
        String text = FileTextExtractor.extract(f, "pptx", StandardCharsets.UTF_8);
        assertThat(text).contains("슬라이드 1").contains("Hello pptx slide");
    }
}
