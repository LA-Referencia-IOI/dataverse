package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.dark.DarkReportBean.AssignedRow;
import java.io.IOException;
import java.io.StringWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/**
 * CSV of the dARK registry ({@code dark-registry.csv}), shared by the API and the
 * dashboard. Neither {@code client_item_id} nor CIDs are included.
 */
public final class DarkCsv {

    public static final String[] HEADER = { "ark", "datasetId", "persistentId", "title", "status", "target",
        "sentVersion", "attempts", "stuck", "lastError", "createdAt", "updatedAt" };

    private DarkCsv() {
    }

    public static String toCsv(Iterable<AssignedRow> rows) throws IOException {
        StringWriter out = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setHeader(HEADER).build())) {
            for (AssignedRow r : rows) {
                csv.printRecord(r.ark(), r.datasetId(), r.persistentId(), r.title(), String.valueOf(r.status().getCode()),
                        r.target(), r.sentVersion(), r.attempts(), r.stuck(), r.lastError(),
                        r.createdAt() == null ? null : r.createdAt().toInstant().toString(),
                        r.updatedAt() == null ? null : r.updatedAt().toInstant().toString());
            }
        }
        return out.toString();
    }
}
