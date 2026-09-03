import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.io.IOException;
import java.util.*;

public class CheckFooters {
    static class LB {
        float minY, maxY;
        String text;
        LB(float minY, float maxY, String text) { this.minY = minY; this.maxY = maxY; this.text = text; }
    }
    static class S extends PDFTextStripper {
        List<LB> lines = new ArrayList<>();
        S() throws IOException { super(); }
        protected void writeString(String s, List<TextPosition> tps) {
            float minY = Float.MAX_VALUE, maxY = 0;
            for (TextPosition tp : tps) {
                minY = Math.min(minY, tp.getY());
                maxY = Math.max(maxY, tp.getY() + tp.getHeight());
            }
            lines.add(new LB(minY, maxY, s.trim()));
        }
    }
    public static void main(String[] args) throws Exception {
        try (PDDocument doc = Loader.loadPDF(new java.io.File(args[0]))) {
            for (int p : new int[]{8, 53, 54}) {
                S s = new S();
                s.setStartPage(p); s.setEndPage(p);
                s.getText(doc);
                float h = doc.getPage(p - 1).getMediaBox().getHeight();
                System.out.println("PDF " + p + " h=" + h);
                for (LB lb : s.lines) {
                    if (lb.text.matches("\\d{1,3}"))
                        System.out.printf("  num=%s minY=%.0f maxY=%.0f top=%s bot=%s%n",
                            lb.text, lb.minY, lb.maxY,
                            lb.minY >= h * 0.85f, lb.maxY <= h * 0.15f);
                }
            }
        }
    }
}
