package desk;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExcelScaffolder {
    private ExcelScaffolder(){}

    static void createEmptyWorkbook(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (Workbook wb = new XSSFWorkbook();
             OutputStream os = Files.newOutputStream(path)) {
            wb.createSheet("Sheet1");
            wb.write(os);
        }
    }
}
