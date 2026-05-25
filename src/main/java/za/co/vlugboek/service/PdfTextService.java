package za.co.vlugboek.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

@Service
public class PdfTextService {
    public String extract(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not extract PDF text from " + path, ex);
        }
    }
}
