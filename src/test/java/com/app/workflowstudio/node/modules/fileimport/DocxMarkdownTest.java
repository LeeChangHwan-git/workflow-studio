package com.app.workflowstudio.node.modules.fileimport;

import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Word → 마크다운: 실제 Word 와 같은 스타일·번호 정의를 넣은 docx 를 만들어 변환해 본다 */
class DocxMarkdownTest {

    @TempDir
    Path dir;

    /** 회의록 모양 Word 파일 (한글 Word 처럼 제목 스타일 id 가 "1", "2" 이고 이름이 heading 1/2) */
    static void writeMeeting(Path file, String title, String decision) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); FileOutputStream out = new FileOutputStream(file.toFile())) {
            XWPFStyles styles = doc.createStyles();
            style(styles, "Title", "Title");
            style(styles, "1", "heading 1");
            style(styles, "2", "heading 2");
            XWPFNumbering numbering = doc.createNumbering();
            BigInteger bullet = numbering.addNum(numbering.addAbstractNum(abstractNum(0, STNumberFormat.BULLET, "•")));
            BigInteger decimal = numbering.addNum(numbering.addAbstractNum(abstractNum(1, STNumberFormat.DECIMAL, "%1.")));

            para(doc, "Title", title);
            para(doc, "1", "참석자");
            XWPFTable t = doc.createTable(3, 2);
            cell(t, 0, 0, "이름"); cell(t, 0, 1, "역할");
            cell(t, 1, 0, "홍길동"); cell(t, 1, 1, "FW 리드");
            cell(t, 2, 0, "김영희"); cell(t, 2, 1, "HW | 회로");
            para(doc, "1", "논의 내용");
            para(doc, "2", "부트로더");
            XWPFParagraph b1 = doc.createParagraph();
            b1.setNumID(bullet);
            b1.createRun().setText("OTA 서명 검증 추가");
            XWPFParagraph mixed = doc.createParagraph();
            mixed.createRun().setText("일정은 ");
            XWPFRun bold = mixed.createRun();
            bold.setBold(true);
            bold.setText("금요일까지 ");
            mixed.createRun().setText("확정");
            para(doc, "1", "결정 사항");
            XWPFParagraph d1 = doc.createParagraph();
            d1.setNumID(decimal);
            d1.createRun().setText(decision);
            XWPFParagraph link = doc.createParagraph();
            XWPFHyperlinkRun hr = link.createHyperlinkRun("https://wiki.example.com/fw");
            hr.setText("회의 자료");
            doc.write(out);
        }
    }

    private static void style(XWPFStyles styles, String id, String name) {
        CTStyle s = CTStyle.Factory.newInstance();
        s.setStyleId(id);
        s.setType(STStyleType.PARAGRAPH);
        s.addNewName().setVal(name);
        styles.addStyle(new XWPFStyle(s));
    }

    private static XWPFAbstractNum abstractNum(int id, STNumberFormat.Enum fmt, String text) {
        CTAbstractNum a = CTAbstractNum.Factory.newInstance();
        a.setAbstractNumId(BigInteger.valueOf(id));
        CTLvl lvl = a.addNewLvl();
        lvl.setIlvl(BigInteger.ZERO);
        lvl.addNewNumFmt().setVal(fmt);
        lvl.addNewLvlText().setVal(text);
        lvl.addNewStart().setVal(BigInteger.ONE);
        return new XWPFAbstractNum(a);
    }

    private static void para(XWPFDocument doc, String styleId, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle(styleId);
        p.createRun().setText(text);
    }

    private static void cell(XWPFTable t, int r, int c, String text) {
        t.getRow(r).getCell(c).setText(text);
    }

    @Test
    void 제목_표_목록_굵게_링크를_마크다운으로_바꾼다() throws Exception {
        Path f = dir.resolve("회의록.docx");
        writeMeeting(f, "주간 FW 회의록 (10/06)", "v2.3 배포 승인");
        String md = FileTextExtractor.extract(f, "docx", StandardCharsets.UTF_8);

        assertThat(md).startsWith("# 주간 FW 회의록 (10/06)\n");
        assertThat(md).contains("\n# 참석자\n").contains("\n## 부트로더\n");
        assertThat(md).contains("| 이름 | 역할 |\n| --- | --- |\n| 홍길동 | FW 리드 |\n| 김영희 | HW \\| 회로 |");
        assertThat(md).contains("\n- OTA 서명 검증 추가\n");
        assertThat(md).contains("\n1. v2.3 배포 승인\n");
        assertThat(md).contains("일정은 **금요일까지** 확정");
        assertThat(md).contains("[회의 자료](https://wiki.example.com/fw)");
        assertThat(md).doesNotContain("\n\n\n");
    }

    @Test
    void 합칠_때_제목을_두_단계_내린다() {
        assertThat(DocxMarkdown.shiftHeadings("# 제목\n본문 #태그\n## 소제목\n###### 끝", 2))
                .isEqualTo("### 제목\n본문 #태그\n#### 소제목\n###### 끝");
    }
}
