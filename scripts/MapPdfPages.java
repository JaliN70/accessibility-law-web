import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.io.IOException;
import java.util.List;

public class MapPdfPages {
    static class FooterStripper extends PDFTextStripper {
        int page = 0;
        String footer = "";

        FooterStripper() throws IOException { super(); }

        @Override
        protected void startPage(org.apache.pdfbox.pdmodel.PDPage pdPage) throws IOException {
            super.startPage(pdPage);
            page = getCurrentPageNo();
            footer = "";
        }

        @Override
        protected void writeString(String string, List<TextPosition> positions) throws IOException {
            if (positions.isEmpty()) return;
            float y = positions.get(0).getY();
            if (y < 80) footer = string.trim();
        }
    }

    public static void main(String[] args) throws Exception {
        try (PDDocument doc = Loader.loadPDF(new java.io.File(args[0]))) {
            FooterStripper s = new FooterStripper();
            s.getText(doc);
            s = new FooterStripper();
            for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                s.setStartPage(i);
                s.setEndPage(i);
                s.getText(doc);
                String f = s.footer.replaceAll("\\s+", "");
                if (f.matches("\\d{1,3}")) {
                    System.out.println("printed " + f + " -> PDF " + i);
                }
            }
        }
    }
}
