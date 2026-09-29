package com.vishwas.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates the demo seed files for Deccan Home Appliances Pvt Ltd (fictional): six monthly purchase
 * registers and simplified GSTR-2B files (April to September 2026), the vendor master, and two vendor
 * letters as PDF. Deterministic (fixed random seed), so regenerating produces identical files.
 * <p>
 * Every scenario invoice is written out explicitly below; the nine reliable vendors get routine random
 * invoices. Run from the project root:
 * {@code mvn -q test-compile exec:java -Dexec.mainClass=com.vishwas.tools.SeedGenerator -Dexec.classpathScope=test}
 */
public final class SeedGenerator {

    static final Path OUT = Path.of("src/main/resources/seed");
    static final String COMPANY_GSTIN = "36AAGCD4821M1ZG";
    static final String COMPANY_STATE = "36";
    static final List<String> PERIODS = List.of("2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09");

    record VendorDef(String key, String gstin, String name, String city, String email, String contact, String phone,
                     String hsn, int rate, String item, String numberFormat, int minTaxable, int maxTaxable,
                     int perMonthMin, int perMonthMax, int seqStart) {
        String state() {
            return gstin.substring(0, 2);
        }
    }

    /** One invoice with everything that can go wrong with it. */
    static final class Inv {
        VendorDef v;
        String period;
        String no;
        String booksNo;
        LocalDate date;
        LocalDate booksDate;
        BigDecimal taxable;
        BigDecimal g2bTaxable;
        boolean g2bIgstOverride;
        String g2bGstin;
        String filedIn;
        String bookedIn;
        boolean duplicateInBooks;
        String item;
        String amendIn;
        BigDecimal amendTaxable;
        String creditNoteIn;
        String creditNoteNo;
        BigDecimal creditNoteTaxable;
    }

    static final VendorDef SBT = new VendorDef("SBT", "36ABKFS2231Q1ZP", "Sri Balaji Traders", "Begum Bazar, Hyderabad",
            "accounts@sribalajitraders.example", "P. Venkatesh", "+91 40 2461 3378", "8536", 18,
            "Switches, sockets and MCBs", "SBT/2026/%04d", 0, 0, 0, 0, 0);
    static final VendorDef SBE = new VendorDef("SBE", "36ADSFS7710L1ZD", "Sri Balaji Enterprises", "Secunderabad",
            "billing@sribalajient.example", "K. Ramesh", "+91 40 2780 5521", "7326", 18,
            "Sheet-metal cabinet parts", "SBE-%04d", 0, 0, 0, 0, 0);
    static final VendorDef KPL = new VendorDef("KPL", "29AAHCK5512D1ZO", "Kaveri Packaging Pvt Ltd", "Peenya, Bengaluru",
            "finance@kaveripackaging.example", "R. Manjunath", "+91 80 2839 4410", "4819", 18,
            "Corrugated cartons", "KPL/%04d", 0, 0, 0, 0, 0);
    static final VendorDef GSI = new VendorDef("GSI", "37AACCG3309R1Z8", "Godavari Steel Industries Ltd", "Visakhapatnam",
            "sales@godavaristeel.example", "S. Rao", "+91 891 256 7710", "7209", 18,
            "CRCA steel sheets", "INV/26-27/%04d", 0, 0, 0, 0, 0);
    static final VendorDef MLS = new VendorDef("MLS", "36AAPFM8841E1ZX", "Metro Logistics", "Kukatpally, Hyderabad",
            "accounts@metrologistics.example", "K. Srikanth", "+91 40 2305 8841", "9965", 5,
            "Road freight (GTA)", "MLS/%04d", 0, 0, 0, 0, 0);
    static final VendorDef NEL = new VendorDef("NEL", "29AAJCN6620H1Z8", "Nandi Electricals Pvt Ltd", "Doddaballapur, Bengaluru",
            "ar@nandielectricals.example", "A. Gowda", "+91 80 2762 6620", "8501", 18,
            "Fan motors and capacitors", "NEL/%04d", 0, 0, 0, 0, 0);
    static final VendorDef CFP = new VendorDef("CFP", "36AAKFC1914B1ZY", "Charminar Fasteners", "Balanagar, Hyderabad",
            "office@charminarfasteners.example", "M. Iqbal", "+91 40 2377 1914", "7318", 18,
            "Screws, nuts and washers", "CFP/%04d", 18000, 76000, 1, 2, 3260);
    static final VendorDef DCW = new VendorDef("DCW", "36AAECD7305N1ZF", "Deccan Copper Wires Pvt Ltd", "Cherlapally, Hyderabad",
            "accounts@deccancopper.example", "V. Naidu", "+91 40 2726 7305", "7408", 18,
            "Enamelled copper winding wire", "DCW/2026/%04d", 140000, 260000, 1, 2, 1120);
    static final VendorDef KRP = new VendorDef("KRP", "37ABBFK4471C1ZJ", "Krishna Polymers", "Vijayawada",
            "sales@krishnapolymers.example", "B. Krishna", "+91 866 247 4471", "3902", 18,
            "Polypropylene granules", "KP/26-27/%03d", 90000, 210000, 1, 2, 40);
    static final VendorDef TMP = new VendorDef("TMP", "29AADCT2256K1Z1", "Tungabhadra Motors Pvt Ltd", "Hospet",
            "finance@tungabhadramotors.example", "N. Hegde", "+91 8394 22 2256", "8501", 18,
            "Mixer-grinder motors", "TMP/%04d", 80000, 160000, 1, 2, 5480);
    static final VendorDef CPL = new VendorDef("CPL", "33AACCC9087F1ZM", "Coromandel Plastics Pvt Ltd", "Ambattur, Chennai",
            "ar@coromandelplastics.example", "S. Iyer", "+91 44 2625 9087", "3926", 18,
            "Injection-moulded housings", "CPL/%04d", 60000, 140000, 1, 2, 4440);
    static final VendorDef HRW = new VendorDef("HRW", "36AAFFH3390J1Z7", "Hyderabad Rubber Works", "Jeedimetla, Hyderabad",
            "hrw.accounts@hydrubber.example", "G. Reddy", "+91 40 2309 3390", "4016", 18,
            "Rubber gaskets and seals", "HRW/%03d", 12000, 38000, 1, 2, 210);
    static final VendorDef PPC = new VendorDef("PPC", "27AAGCP5528G1ZA", "Pune Precision Components Pvt Ltd", "Chakan, Pune",
            "billing@puneprecision.example", "A. Kulkarni", "+91 20 2745 5528", "8483", 18,
            "Machined shafts and bushes", "PPC/26-27/%04d", 70000, 180000, 1, 2, 330);
    static final VendorDef GIS = new VendorDef("GIS", "36AAKFG7719P1ZM", "Golconda IT Services LLP", "Madhapur, Hyderabad",
            "billing@golcondait.example", "R. Sharma", "+91 40 4012 7719", "998313", 18,
            "IT support and AMC", "GIS/26-27/%03d", 0, 0, 0, 0, 0);
    static final VendorDef SFI = new VendorDef("SFI", "27ABAFS6604M1ZX", "Sahyadri Foam Industries", "Bhosari, Pune",
            "accounts@sahyadrifoam.example", "P. Deshmukh", "+91 20 2712 6604", "3921", 18,
            "PU foam inserts", "SFI/%03d", 25000, 65000, 1, 2, 520);

    static final List<VendorDef> VENDORS = List.of(SBT, SBE, KPL, GSI, MLS, NEL, CFP, DCW, KRP, TMP, CPL, HRW, PPC, GIS, SFI);
    static final List<VendorDef> ROUTINE = List.of(CFP, DCW, KRP, TMP, CPL, HRW, PPC, SFI);
    static final String TMP_TS_BRANCH = "36AADCT2256K2Z5";

    private final Random random = new Random(2026);
    private final List<Inv> invoices = new ArrayList<>();
    private final Map<String, Integer> seq = new LinkedHashMap<>();
    private final Set<String> used = new HashSet<>();

    public static void main(String[] args) throws IOException {
        new SeedGenerator().run();
    }

    void run() throws IOException {
        scenario();
        routine();
        writeVendors();
        for (String p : PERIODS) {
            writeBooks(p);
            writeGstr2b(p);
        }
        writeLetters();
        System.out.println("Seed written to " + OUT.toAbsolutePath() + " (" + invoices.size() + " invoices)");
    }

    // ------------------------------------------------------------------ the story, vendor by vendor

    void scenario() {
        // Sri Balaji Traders: TIMING only. Missing invoices appeared in the next GSTR-2B 4 of 4 times.
        inv(SBT, "2026-04", 29, 3, "64500");
        inv(SBT, "2026-04", 31, 9, "58000").filedIn = "2026-05";
        inv(SBT, "2026-04", 38, 22, "71200").filedIn = "2026-05";
        inv(SBT, "2026-05", 44, 6, "49800");
        inv(SBT, "2026-05", 51, 14, "83600");
        inv(SBT, "2026-05", 57, 26, "61400").filedIn = "2026-06";
        inv(SBT, "2026-06", 63, 5, "77000");
        inv(SBT, "2026-06", 72, 17, "52300");
        inv(SBT, "2026-06", 79, 27, "68900").filedIn = "2026-07";
        inv(SBT, "2026-07", 86, 4, "91500");
        inv(SBT, "2026-07", 93, 15, "57700");
        inv(SBT, "2026-07", 101, 24, "66000");
        inv(SBT, "2026-08", 107, 5, "72400");
        inv(SBT, "2026-08", 112, 13, "48600");
        inv(SBT, "2026-08", 118, 25, "93000").filedIn = "2026-09";
        inv(SBT, "2026-09", 124, 4, "69000");
        inv(SBT, "2026-09", 131, 16, "55500");
        inv(SBT, "2026-09", 137, 24, "80200");

        // Sri Balaji Enterprises (look-alike, different GSTIN): never late, but 2 AMOUNT_MISMATCH cases.
        inv(SBE, "2026-04", 2184, 8, "112000");
        Inv sbe2190 = inv(SBE, "2026-04", 2190, 19, "168000");
        sbe2190.g2bTaxable = bd("186000");
        sbe2190.creditNoteIn = "2026-05";
        sbe2190.creditNoteNo = "SBE/CN/0031";
        sbe2190.creditNoteTaxable = bd("18000");
        inv(SBE, "2026-05", 2205, 7, "96500");
        Inv sbe2211 = inv(SBE, "2026-05", 2211, 21, "142000");
        sbe2211.g2bTaxable = bd("124200");
        sbe2211.amendIn = "2026-06";
        sbe2211.amendTaxable = bd("142000");
        inv(SBE, "2026-06", 2236, 10, "135500");
        inv(SBE, "2026-06", 2249, 24, "108000");
        inv(SBE, "2026-07", 2266, 9, "121000");
        inv(SBE, "2026-07", 2278, 23, "99500");
        inv(SBE, "2026-08", 2304, 6, "116000");
        inv(SBE, "2026-08", 2331, 22, "310000").filedIn = "2026-09"; // first-ever late filing: thin TIMING history
        inv(SBE, "2026-09", 2352, 8, "104000");
        inv(SBE, "2026-09", 2367, 21, "127500");

        // Kaveri Packaging: TIMING + poor RESPONSIVENESS. One of two invoices a month goes unreported.
        inv(KPL, "2026-04", 441, 2, "52000");
        inv(KPL, "2026-04", 456, 16, "48000").filedIn = null;        // never filed: ITC reversed in Aug (confirmed loss)
        inv(KPL, "2026-05", 488, 5, "55500");
        inv(KPL, "2026-05", 502, 19, "70000").filedIn = null;        // Rs 12,600
        inv(KPL, "2026-06", 531, 4, "49000");
        inv(KPL, "2026-06", 547, 18, "75000").filedIn = "2026-09";   // Rs 13,500, filed 3 months late
        inv(KPL, "2026-07", 574, 3, "58500");
        inv(KPL, "2026-07", 589, 17, "88333.33").filedIn = null;     // Rs 15,900 -> May-Jul exposure Rs 42,000
        inv(KPL, "2026-08", 617, 4, "62000");
        inv(KPL, "2026-08", 631, 18, "60000").filedIn = null;        // Rs 10,800
        inv(KPL, "2026-09", 655, 3, "57000");
        inv(KPL, "2026-09", 668, 17, "64500");

        // Godavari Steel: INVOICE_FORMAT only. Stores clerk books "142"; the vendor reports "INV/26-27/0142".
        int[][] gsi = {{21, 11, 286000}, {48, 12, 324000}, {77, 10, 298500}, {102, 9, 341000}, {142, 8, 310000}, {167, 10, 305000}};
        for (int i = 0; i < gsi.length; i++) {
            Inv g = inv(GSI, PERIODS.get(i), gsi[i][0], gsi[i][1], String.valueOf(gsi[i][2]));
            g.booksNo = String.valueOf(gsi[i][0]);
        }

        // Metro Logistics: TAX_HEAD. Reports IGST on an intra-state supply; amends after one follow-up.
        inv(MLS, "2026-04", 1011, 8, "32000");
        inv(MLS, "2026-04", 1024, 23, "28500");
        inv(MLS, "2026-05", 1036, 4, "30500");
        Inv m1043 = inv(MLS, "2026-05", 1043, 9, "36000");
        m1043.g2bIgstOverride = true;
        m1043.amendIn = "2026-06";
        inv(MLS, "2026-06", 1068, 6, "34000");
        Inv m1077 = inv(MLS, "2026-06", 1077, 20, "42000");
        m1077.g2bIgstOverride = true;
        m1077.amendIn = "2026-07";
        inv(MLS, "2026-07", 1092, 8, "29000");
        inv(MLS, "2026-07", 1104, 22, "38500");
        inv(MLS, "2026-08", 1119, 5, "31500");
        Inv m1131 = inv(MLS, "2026-08", 1131, 19, "40000");
        m1131.g2bIgstOverride = true;
        m1131.amendIn = "2026-09";
        inv(MLS, "2026-09", 1146, 7, "33000");
        inv(MLS, "2026-09", 1158, 21, "36500");

        // Nandi Electricals: AMOUNT_ACCURACY. Reports list price; settles the difference by credit note.
        Inv n7710 = inv(NEL, "2026-04", 7710, 14, "196000");
        n7710.g2bTaxable = bd("210000");
        n7710.creditNoteIn = "2026-05";
        n7710.creditNoteNo = "NEL/CN/031";
        n7710.creditNoteTaxable = bd("14000");
        inv(NEL, "2026-05", 7742, 13, "174000");
        Inv n7788 = inv(NEL, "2026-06", 7788, 15, "158500");
        n7788.g2bTaxable = bd("165000");
        n7788.creditNoteIn = "2026-07";
        n7788.creditNoteNo = "NEL/CN/038";
        n7788.creditNoteTaxable = bd("6500");
        inv(NEL, "2026-07", 7815, 14, "182000");
        Inv n7851 = inv(NEL, "2026-08", 7851, 12, "150000");
        n7851.g2bTaxable = bd("159000");
        n7851.creditNoteIn = "2026-09";
        n7851.creditNoteNo = "NEL/CN/044";
        n7851.creditNoteTaxable = bd("9000");
        inv(NEL, "2026-09", 7880, 11, "169000");

        // Golconda IT: monthly AMC (reliable). In August an extra invoice dated 31 Aug is booked only in September.
        int[][] gis = {{15, 25}, {31, 25}, {47, 25}, {66, 25}, {79, 25}, {97, 25}};
        for (int i = 0; i < gis.length; i++) {
            inv(GIS, PERIODS.get(i), gis[i][0], gis[i][1], "38500");
        }
        Inv g088 = inv(GIS, "2026-08", 88, 31, "45000");
        g088.item = "Server upgrade and data migration";
        g088.bookedIn = "2026-09";

        // August one-offs among reliable vendors.
        Inv dup = inv(CFP, "2026-08", 3318, 5, "72000");
        dup.duplicateInBooks = true;                                    // same invoice booked twice -> ESCALATE
        Inv dcw = inv(DCW, "2026-08", 1178, 14, "215000");
        dcw.booksNo = "DCW/2026/1187";                                  // transposed digits in our books -> candidate
        Inv tmp = inv(TMP, "2026-08", 5520, 18, "120000");
        tmp.g2bGstin = TMP_TS_BRANCH;                                   // billed from their Telangana registration
        Inv cpl = inv(CPL, "2026-08", 4471, 12, "84000");
        cpl.booksDate = LocalDate.of(2026, 8, 21);                      // date keyed as 21 instead of 12
    }

    /** Routine invoices for the reliable vendors: 1-2 a month, on time, identical on both sides. */
    void routine() {
        for (String p : PERIODS) {
            YearMonth ym = YearMonth.parse(p);
            for (VendorDef v : ROUTINE) {
                int n = v.perMonthMin() + random.nextInt(v.perMonthMax() - v.perMonthMin() + 1);
                for (int i = 0; i < n; i++) {
                    int number = seq.merge(v.key(), v.seqStart() + 3 + random.nextInt(7), (a, b) -> a + 3 + random.nextInt(7));
                    while (used.contains(v.key() + number)) {
                        number++;
                    }
                    int day = 2 + random.nextInt(ym.lengthOfMonth() - 4);
                    int taxable = (v.minTaxable() + random.nextInt(v.maxTaxable() - v.minTaxable())) / 500 * 500;
                    inv(v, p, number, day, String.valueOf(taxable));
                }
            }
        }
    }

    Inv inv(VendorDef v, String period, int number, int day, String taxable) {
        Inv i = new Inv();
        i.v = v;
        i.period = period;
        i.no = String.format(v.numberFormat(), number);
        i.booksNo = i.no;
        i.date = YearMonth.parse(period).atDay(day);
        i.taxable = bd(taxable);
        i.g2bTaxable = i.taxable;
        i.filedIn = period;
        i.bookedIn = period;
        i.item = v.item();
        used.add(v.key() + number);
        invoices.add(i);
        return i;
    }

    // ------------------------------------------------------------------ writers

    void writeVendors() throws IOException {
        List<Map<String, Object>> list = new ArrayList<>();
        for (VendorDef v : VENDORS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gstin", v.gstin());
            m.put("legal_name", v.name());
            m.put("city", v.city());
            m.put("state_code", v.state());
            m.put("email", v.email());
            m.put("contact_person", v.contact());
            m.put("phone", v.phone());
            m.put("supplies", v.item() + " (HSN " + v.hsn() + ", " + v.rate() + "%)");
            list.add(m);
        }
        Files.createDirectories(OUT);
        json().writeValue(OUT.resolve("vendors.json").toFile(), list);
    }

    void writeBooks(String period) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (Inv i : invoices) {
            if (!period.equals(i.bookedIn)) {
                continue;
            }
            LocalDate invDate = i.booksDate != null ? i.booksDate : i.date;
            LocalDate booked = clamp(invDate.plusDays(random.nextInt(4)), period);
            rows.add(booksRow(i, invDate, booked));
            if (i.duplicateInBooks) {
                rows.add(booksRow(i, invDate, clamp(invDate.plusDays(11), period)));
            }
        }
        rows.sort(Comparator.comparing((String[] r) -> r[1]).thenComparing(r -> r[4]));
        StringBuilder csv = new StringBuilder("voucher_no,booking_date,supplier_gstin,supplier_name,invoice_no,invoice_date,"
                + "place_of_supply,hsn,description,taxable_value,gst_rate,igst,cgst,sgst,invoice_total\n");
        int voucher = 1 + PERIODS.indexOf(period) * 60;
        for (String[] r : rows) {
            r[0] = String.format("PV/26-27/%04d", voucher++);
            r[1] = LocalDate.parse(r[1]).format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            r[5] = LocalDate.parse(r[5]).format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"));
            csv.append(String.join(",", quote(r))).append('\n');
        }
        Path dir = OUT.resolve(period);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("purchase-register.csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    String[] booksRow(Inv i, LocalDate invDate, LocalDate booked) {
        Tax t = tax(i.v, i.taxable, false);
        BigDecimal total = i.taxable.add(t.igst).add(t.cgst).add(t.sgst);
        return new String[]{"", booked.toString(), i.v.gstin(), i.v.name(), i.booksNo, invDate.toString(), i.v.state(),
                i.v.hsn(), i.item, money(i.taxable), String.valueOf(i.v.rate()), money(t.igst), money(t.cgst), money(t.sgst), money(total)};
    }

    void writeGstr2b(String period) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "vishwas-gstr2b-simplified/1");
        root.put("disclaimer", "Simplified GSTR-2B for Vishwas sample data. Not the GST portal schema.");
        root.put("recipient_gstin", COMPANY_GSTIN);
        root.put("return_period", period);
        root.put("generated_on", YearMonth.parse(period).plusMonths(1).atDay(14).toString());

        Map<String, List<Map<String, Object>>> b2b = new TreeMap<>();
        Map<String, List<Map<String, Object>>> b2ba = new TreeMap<>();
        Map<String, List<Map<String, Object>>> cdnr = new TreeMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        List<Inv> sorted = invoices.stream().sorted(Comparator.comparing((Inv x) -> x.date).thenComparing(x -> x.no)).toList();
        for (Inv i : sorted) {
            String supplier = i.g2bGstin != null ? i.g2bGstin : i.v.gstin();
            names.put(supplier, i.v.name());
            if (period.equals(i.filedIn)) {
                Tax t = tax(i.v, i.g2bTaxable, i.g2bIgstOverride, supplier);
                b2b.computeIfAbsent(supplier, k -> new ArrayList<>()).add(invoiceJson(i.no, i.date, supplier, i.g2bTaxable, i.v.rate(), t, null, null));
            }
            if (period.equals(i.amendIn)) {
                BigDecimal taxable = i.amendTaxable != null ? i.amendTaxable : i.taxable;
                Tax t = tax(i.v, taxable, false, supplier);
                b2ba.computeIfAbsent(supplier, k -> new ArrayList<>()).add(invoiceJson(i.no, i.date, supplier, taxable, i.v.rate(), t, i.no, i.date));
            }
            if (period.equals(i.creditNoteIn)) {
                Tax t = tax(i.v, i.creditNoteTaxable, false, supplier);
                Map<String, Object> note = new LinkedHashMap<>();
                note.put("note_type", "C");
                note.put("note_no", i.creditNoteNo);
                note.put("note_date", YearMonth.parse(period).atDay(12).toString());
                note.put("original_invoice_no", i.no);
                note.put("taxable_value", i.creditNoteTaxable);
                note.put("rate", i.v.rate());
                note.put("igst", t.igst);
                note.put("cgst", t.cgst);
                note.put("sgst", t.sgst);
                cdnr.computeIfAbsent(supplier, k -> new ArrayList<>()).add(note);
            }
        }
        root.put("b2b", suppliers(b2b, names, period, "invoices"));
        root.put("b2ba", suppliers(b2ba, names, period, "invoices"));
        root.put("cdnr", suppliers(cdnr, names, period, "notes"));
        json().writeValue(OUT.resolve(period).resolve("gstr2b.json").toFile(), root);
    }

    static List<Map<String, Object>> suppliers(Map<String, List<Map<String, Object>>> bySupplier, Map<String, String> names,
                                               String period, String listName) {
        List<Map<String, Object>> out = new ArrayList<>();
        bySupplier.forEach((gstin, rows) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("supplier_gstin", gstin);
            s.put("supplier_name", names.get(gstin));
            s.put("gstr1_period", period);
            s.put("gstr1_filed_on", YearMonth.parse(period).plusMonths(1).atDay(11).toString());
            s.put(listName, rows);
            out.add(s);
        });
        return out;
    }

    static Map<String, Object> invoiceJson(String no, LocalDate date, String supplier, BigDecimal taxable, int rate, Tax t,
                                           String originalNo, LocalDate originalDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (originalNo != null) {
            m.put("original_invoice_no", originalNo);
            m.put("original_invoice_date", originalDate.toString());
        }
        m.put("invoice_no", no);
        m.put("invoice_date", date.toString());
        m.put("place_of_supply", COMPANY_STATE);
        m.put("taxable_value", taxable);
        m.put("rate", rate);
        m.put("igst", t.igst);
        m.put("cgst", t.cgst);
        m.put("sgst", t.sgst);
        return m;
    }

    void writeLetters() throws IOException {
        Path dir = OUT.resolve("letters");
        Files.createDirectories(dir);
        Files.write(dir.resolve("kaveri-packaging-2026-07-06.pdf"), SimplePdf.render(List.of(
                "# KAVERI PACKAGING PVT LTD",
                "No. 41, 2nd Phase, Peenya Industrial Area, Bengaluru 560058   |   GSTIN 29AAHCK5512D1ZO",
                "",
                "Date: 6 July 2026",
                "",
                "To: Ms Lakshmi Prasad, Accounts, Deccan Home Appliances Pvt Ltd, Hyderabad",
                "",
                "Subject: Pending GSTR-1 filing for invoices KPL/0456, KPL/0502 and KPL/0547",
                "",
                "Dear Madam,",
                "",
                "With reference to your e-mail of 18 June 2026 and your call on 2 July 2026, we regret the delay in "
                        + "reporting the above invoices in our GSTR-1. The delay was caused by a change in our accounts team.",
                "",
                "We assure you that all three invoices - KPL/0456 dated 16 April 2026 (ITC Rs 8,640), KPL/0502 dated "
                        + "19 May 2026 (ITC Rs 12,600) and KPL/0547 dated 18 June 2026 (ITC Rs 13,500), total ITC Rs 34,740 - "
                        + "will be filed in our GSTR-1 by 20 July 2026 and will reflect in your GSTR-2B.",
                "",
                "We value our association and request your continued support.",
                "",
                "Yours faithfully,",
                "R. Manjunath",
                "Finance Manager, Kaveri Packaging Pvt Ltd")));
        Files.write(dir.resolve("metro-logistics-2026-06-19.pdf"), SimplePdf.render(List.of(
                "# METRO LOGISTICS",
                "Plot 12, IDA Kukatpally, Hyderabad 500072   |   GSTIN 36AAPFM8841E1ZX",
                "",
                "Date: 19 June 2026",
                "",
                "To: Ms Lakshmi Prasad, Accounts, Deccan Home Appliances Pvt Ltd, Hyderabad",
                "",
                "Subject: Tax head on invoice MLS/1043 dated 9 May 2026",
                "",
                "Dear Madam,",
                "",
                "Thank you for your e-mail of 16 June 2026. We acknowledge that invoice MLS/1043 (taxable value "
                        + "Rs 36,000, GST Rs 1,800) was reported in our GSTR-1 with IGST instead of CGST + SGST because of "
                        + "an incorrect place-of-supply setting in our billing software.",
                "",
                "We will amend the invoice in our GSTR-1 for June 2026, to be filed by 11 July 2026, so that it "
                        + "reflects correctly as CGST Rs 900 + SGST Rs 900 in your GSTR-2B.",
                "",
                "Regards,",
                "K. Srikanth",
                "Accounts, Metro Logistics")));
    }

    // ------------------------------------------------------------------ helpers

    record Tax(BigDecimal igst, BigDecimal cgst, BigDecimal sgst) {
    }

    static Tax tax(VendorDef v, BigDecimal taxable, boolean forceIgst) {
        return tax(v, taxable, forceIgst, v.gstin());
    }

    /** Intra-state (supplier registered in Telangana) = CGST + SGST; otherwise IGST. */
    static Tax tax(VendorDef v, BigDecimal taxable, boolean forceIgst, String supplierGstin) {
        BigDecimal rate = BigDecimal.valueOf(v.rate());
        boolean intra = supplierGstin.startsWith(COMPANY_STATE) && !forceIgst;
        if (intra) {
            BigDecimal half = taxable.multiply(rate).divide(BigDecimal.valueOf(200), 2, RoundingMode.HALF_UP);
            return new Tax(zero(), half, half);
        }
        return new Tax(taxable.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP), zero(), zero());
    }

    static BigDecimal bd(String s) {
        return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }

    static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static LocalDate clamp(LocalDate d, String period) {
        LocalDate end = YearMonth.parse(period).atEndOfMonth();
        return d.isAfter(end) ? end : d;
    }

    static String[] quote(String[] r) {
        String[] out = new String[r.length];
        for (int i = 0; i < r.length; i++) {
            out[i] = r[i].contains(",") || r[i].contains("\"") ? "\"" + r[i].replace("\"", "\"\"") + "\"" : r[i];
        }
        return out;
    }

    static ObjectMapper json() {
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }
}
