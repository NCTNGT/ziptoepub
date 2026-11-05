package vn.zipepub.studio;

import android.content.Context;
import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Native streaming EPUB exporter.  It holds only one input ZIP and a single page in
 * memory at a time, and writes the finished EPUB directly to a SAF document Uri.
 */
public final class EpubExporter {
    private EpubExporter() {}

    public static class Chapter {
        public final Uri uri;
        public final String fileName;
        public String title;
        public final int pageCount;
        public Chapter(Uri uri, String fileName, String title, int pageCount) {
            this.uri = uri;
            this.fileName = fileName;
            this.title = title;
            this.pageCount = pageCount;
        }
    }

    public interface Progress {
        void update(int current, int total, String message);
    }

    private static final String[] SUPPORTED = {"jpg", "jpeg", "jfif", "png", "gif", "webp", "avif", "bmp"};

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "";
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean eligible(String path) {
        if (path == null || path.isEmpty()) return false;
        String[] parts = path.replace('\\', '/').split("/");
        for (String p : parts) {
            if (p.startsWith(".") || p.equalsIgnoreCase("__MACOSX") || p.equalsIgnoreCase("Thumbs.db")) return false;
        }
        String ext = extension(path);
        for (String s : SUPPORTED) if (ext.equals(s)) return true;
        return false;
    }

    public static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String v = c.getString(0);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Exception ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "Tệp" : last;
    }

    public static String titleForFile(String fileName) {
        return fileName.replaceFirst("(?i)\\.(zip|cbz)$", "").replace('_', ' ').trim();
    }

    private static File cacheZip(Context ctx, Uri uri) throws IOException {
        File temp = File.createTempFile("epub_zip_", ".zip", ctx.getCacheDir());
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) throw new IOException("Không mở được ZIP: " + displayName(ctx, uri));
            transfer(in, out);
            return temp;
        } catch (Exception e) {
            temp.delete();
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException("Không sao chép được ZIP: " + e.getMessage(), e);
        }
    }

    private static void transfer(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[128 * 1024];
        int count;
        while ((count = in.read(buf)) >= 0) if (count > 0) out.write(buf, 0, count);
    }

    private static int compareNatural(String a, String b) {
        a = a.replace('\\', '/'); b = b.replace('\\', '/');
        int x = 0, y = 0;
        while (x < a.length() && y < b.length()) {
            char ca = a.charAt(x), cb = b.charAt(y);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int a0 = x, b0 = y;
                while (x < a.length() && Character.isDigit(a.charAt(x))) x++;
                while (y < b.length() && Character.isDigit(b.charAt(y))) y++;
                int i = a0, j = b0;
                while (i < x && a.charAt(i) == '0') i++;
                while (j < y && b.charAt(j) == '0') j++;
                int la = x - i, lb = y - j;
                if (la != lb) return Integer.compare(la, lb);
                for (int p = 0; p < la; p++) {
                    int delta = Character.compare(a.charAt(i + p), b.charAt(j + p));
                    if (delta != 0) return delta;
                }
                int zeros = Integer.compare(x - a0, y - b0);
                if (zeros != 0) return zeros;
            } else {
                int d = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (d != 0) return d;
                x++; y++;
            }
        }
        return Integer.compare(a.length() - x, b.length() - y);
    }

    private static List<ZipEntry> orderedImages(ZipFile zf) throws IOException {
        ArrayList<ZipEntry> list = new ArrayList<>();
        Enumeration<? extends ZipEntry> e = zf.entries();
        while (e.hasMoreElements()) {
            ZipEntry entry = e.nextElement();
            if (!entry.isDirectory() && eligible(entry.getName())) list.add(entry);
        }
        Collections.sort(list, (a, b) -> compareNatural(a.getName(), b.getName()));
        return list;
    }

    public static int countImages(Context ctx, Uri zipUri) throws IOException {
        File tmp = cacheZip(ctx, zipUri);
        try (ZipFile z = new ZipFile(tmp)) {
            return orderedImages(z).size();
        } finally {
            if (!tmp.delete()) tmp.deleteOnExit();
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void addText(ZipOutputStream out, String path, String content) throws IOException {
        out.setLevel(6);
        ZipEntry e = new ZipEntry("OEBPS/" + path);
        out.putNextEntry(e);
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static void startImage(ZipOutputStream out, String path) throws IOException {
        out.setLevel(1);
        out.putNextEntry(new ZipEntry("OEBPS/Images/" + path));
    }

    private static String canonicalExt(String ext) {
        if (ext.equals("jpeg") || ext.equals("jfif")) return "jpg";
        if (ext.equals("jpg") || ext.equals("png") || ext.equals("gif")) return ext;
        return "png";
    }

    private static String mime(String ext) {
        if (ext.equals("jpg")) return "image/jpeg";
        if (ext.equals("gif")) return "image/gif";
        return "image/png";
    }

    private static void convertToPng(InputStream in, ZipOutputStream out, String path) throws IOException {
        Bitmap bmp = null;
        try {
            bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) throw new IOException("Android không giải mã được ảnh: " + path);
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("Không chuyển được ảnh sang PNG: " + path);
            }
        } catch (OutOfMemoryError oom) {
            throw new IOException("Ảnh quá lớn để giải mã: " + path, oom);
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private static void writeZipImage(ZipOutputStream out, ZipFile inputZip, ZipEntry entry, String path) throws IOException {
        startImage(out, path);
        try (InputStream in = inputZip.getInputStream(entry)) {
            if (entry.getSize() == 0) throw new IOException("Ảnh trống trong ZIP: " + entry.getName());
            String ext = extension(entry.getName());
            if (canonicalExt(ext).equals("png") && !ext.equals("png")) {
                convertToPng(in, out, entry.getName());
            } else {
                CheckedInputStream checked = new CheckedInputStream(in, new CRC32());
                transfer(checked, out);
                if (entry.getCrc() >= 0 && checked.getChecksum().getValue() != entry.getCrc()) {
                    throw new IOException("Ảnh bị hỏng (CRC): " + entry.getName());
                }
            }
        } finally { out.closeEntry(); }
    }

    private static String selectedExt(Context ctx, Uri uri) {
        String ext = extension(displayName(ctx, uri));
        if (!ext.isEmpty()) return ext;
        String mime = ctx.getContentResolver().getType(uri);
        if ("image/jpeg".equals(mime)) return "jpg";
        if ("image/png".equals(mime)) return "png";
        if ("image/gif".equals(mime)) return "gif";
        if ("image/webp".equals(mime)) return "webp";
        return "png";
    }

    private static String writeSelectedPicture(Context ctx, ZipOutputStream out, Uri picture, String prefix) throws IOException {
        if (picture == null) return null;
        String ext = selectedExt(ctx, picture);
        String destination = prefix + "." + canonicalExt(ext);
        startImage(out, destination);
        try (InputStream in = ctx.getContentResolver().openInputStream(picture)) {
            if (in == null) throw new IOException("Không mở được " + displayName(ctx, picture));
            if (canonicalExt(ext).equals("png") && !ext.equals("png")) convertToPng(in, out, displayName(ctx, picture));
            else transfer(in, out);
        } finally { out.closeEntry(); }
        return destination;
    }

    private static void generatedCover(ZipOutputStream out, String title) throws IOException {
        Bitmap bitmap;
        try { bitmap = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888); }
        catch (OutOfMemoryError oom) { throw new IOException("Không đủ RAM tạo bìa mặc định.", oom); }
        try {
            Canvas cv = new Canvas(bitmap);
            Paint p = new Paint(3);
            p.setShader(new LinearGradient(0, 0, 1200, 1800,
                    Color.rgb(31, 47, 88), Color.rgb(69, 98, 180), Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, 1200, 1800, p);
            p.setShader(null); p.setColor(Color.WHITE);
            p.setTextSize(64f); p.setFakeBoldText(true);
            String[] tokens = title.split("\\s+");
            StringBuilder line = new StringBuilder();
            int y = 420, lines = 0;
            for (String token : tokens) {
                String next = line.length() == 0 ? token : line + " " + token;
                if (p.measureText(next) > 990 && line.length() > 0) {
                    cv.drawText(line.toString(), 105, y, p); y += 100; lines++;
                    line.setLength(0); line.append(token);
                } else { line.setLength(0); line.append(next); }
                if (lines > 10) break;
            }
            if (line.length() > 0) cv.drawText(line.toString(), 105, y, p);
            p.setTextSize(33); p.setFakeBoldText(false); p.setColor(Color.rgb(206, 218, 255));
            cv.drawText("ZIP TO EPUB STUDIO", 105, 1580, p);
            startImage(out, "cover.png");
            try { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("Tạo bìa thất bại."); }
            finally { out.closeEntry(); }
        } finally { bitmap.recycle(); }
    }

    private static String wrapPage(String img, boolean scroll, boolean background) {
        String cls = scroll ? "scroll" : "fixed";
        String viewport = scroll ? "" : "<meta name=\"viewport\" content=\"width=1200,height=1800\"/>";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"vi\"><head>" + viewport
             + "<title>Trang truyện</title><link rel=\"stylesheet\" type=\"text/css\" href=\"../Styles/style.css\"/></head>"
             + "<body class=\"" + cls + (background ? " hasBg" : "") + "\">"
             + "<img class=\"pageImage\" src=\"../Images/" + escape(img) + "\" alt=\"Trang truyện\"/>"
             + "</body></html>";
    }

    private static String scrollChapter(List<String> images, String title, boolean background) {
        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
         .append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"vi\"><head><title>")
         .append(escape(title)).append("</title><link rel=\"stylesheet\" type=\"text/css\" href=\"../Styles/style.css\"/>")
         .append("</head><body class=\"scroll").append(background ? " hasBg" : "").append("\"><main>");
        for (int i = 0; i < images.size(); i++) {
            b.append("<figure><img src=\"../Images/").append(escape(images.get(i)))
             .append("\" alt=\"Trang ").append(i + 1).append("\"/></figure>");
        }
        return b.append("</main></body></html>").toString();
    }

    private static String css(boolean withBackground) {
        String background = withBackground ? "background-image:url('../Images/background.png');" : "";
        // Actual background extension is substituted by the caller when necessary.
        return "@charset \"UTF-8\";\n@page {margin:0;padding:0;}\n"
            + "*{box-sizing:border-box;}html,body{padding:0;margin:0;}\n"
            + "body.fixed {width:1200px;height:1800px;overflow:hidden;display:flex;align-items:center;justify-content:center;}\n"
            + "body.fixed.hasBg {" + background + "background-size:cover;background-position:center center;background-repeat:no-repeat;}\n"
            + "body.fixed .pageImage {display:block;max-width:100%;max-height:100%;width:auto;height:auto;object-fit:contain;}\n"
            + "body.cover{margin:0;padding:0;} body.cover img {display:block;width:100%;height:auto;max-height:100vh;object-fit:contain;}\n"
            + "body.scroll {width:100%;height:auto;} body.scroll.hasBg {" + background
            + "background-position:top center;background-size:100% auto;background-repeat:repeat-y;}\n"
            + "body.scroll main,body.scroll figure {margin:0;padding:0;}\n"
            + "body.scroll figure {line-height:0;break-inside:avoid;page-break-inside:avoid;}\n"
            + "body.scroll figure img {display:block;width:100%;height:auto;}\n";
    }

    public static void export(Context ctx, Uri outputUri, List<Chapter> chapters,
                              Uri coverUri, Uri backgroundUri, String title, String author,
                              boolean scroll, boolean rtl, Progress progress) throws IOException {
        if (chapters.isEmpty()) throw new IOException("Chưa chọn chương nào.");
        int expected = 0;
        for (Chapter c : chapters) expected += c.pageCount;
        final int total = expected;
        StringBuilder manifest = new StringBuilder();
        StringBuilder spine = new StringBuilder();
        StringBuilder nav = new StringBuilder();
        StringBuilder ncx = new StringBuilder();
        String uuid = "urn:uuid:" + UUID.randomUUID();
        int actual = 0;

        OutputStream fileOutput = ctx.getContentResolver().openOutputStream(outputUri, "w");
        if (fileOutput == null) throw new IOException("Không mở được file EPUB đích.");
        try (OutputStream output = fileOutput; ZipOutputStream z = new ZipOutputStream(output)) {
            // EPUB requirement: the first entry must be uncompressed "mimetype".
            byte[] mimeData = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32(); crc.update(mimeData);
            ZipEntry mt = new ZipEntry("mimetype");
            mt.setMethod(ZipEntry.STORED); mt.setSize(mimeData.length);
            mt.setCompressedSize(mimeData.length); mt.setCrc(crc.getValue());
            z.putNextEntry(mt); z.write(mimeData); z.closeEntry();
            z.setLevel(6);
            z.putNextEntry(new ZipEntry("META-INF/container.xml"));
            z.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                 + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                 + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                 + "</rootfiles></container>").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();

            Uri finalCover = coverUri == null ? backgroundUri : coverUri;
            String coverPath;
            if (finalCover != null) coverPath = writeSelectedPicture(ctx, z, finalCover, "cover");
            else { generatedCover(z, title); coverPath = "cover.png"; }
            String bgPath = backgroundUri == null ? null : writeSelectedPicture(ctx, z, backgroundUri, "background");
            manifest.append("<item id=\"cover-image\" href=\"Images/").append(coverPath)
                    .append("\" media-type=\"").append(mime(extension(coverPath))).append("\" properties=\"cover-image\"/>");
            manifest.append("<item id=\"cover-page\" href=\"Text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>");
            if (bgPath != null) manifest.append("<item id=\"bg-image\" href=\"Images/").append(bgPath)
                    .append("\" media-type=\"").append(mime(extension(bgPath))).append("\"/>");
            spine.append("<itemref idref=\"cover-page\"/>");
            addText(z, "Text/cover.xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"vi\"><head><title>Bìa</title>"
                    + "<link rel=\"stylesheet\" href=\"../Styles/style.css\" type=\"text/css\"/></head>"
                    + "<body class=\"cover\"><img src=\"../Images/" + coverPath + "\" alt=\"Bìa sách\"/></body></html>");

            String cssData = css(bgPath != null);
            if (bgPath != null) cssData = cssData.replace("background.png", bgPath);
            addText(z, "Styles/style.css", cssData);
            manifest.append("<item id=\"style\" href=\"Styles/style.css\" media-type=\"text/css\"/>");
            manifest.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>");
            manifest.append("<item id=\"toc-ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>");

            for (int ci = 0; ci < chapters.size(); ci++) {
                Chapter chapter = chapters.get(ci);
                if (progress != null) progress.update(actual, total, "Đang xử lý: " + chapter.title);
                File tmp = cacheZip(ctx, chapter.uri);
                try (ZipFile inputZip = new ZipFile(tmp)) {
                    List<ZipEntry> pages = orderedImages(inputZip);
                    if (pages.isEmpty() || pages.size() != chapter.pageCount) {
                        throw new IOException("Chương '" + chapter.title + "' thay đổi số ảnh: " + pages.size()
                                + "/" + chapter.pageCount + ". Hãy chọn lại ZIP.");
                    }
                    ArrayList<String> chapterImages = new ArrayList<>();
                    String firstHref = null;
                    for (int pi = 0; pi < pages.size(); pi++) {
                        ZipEntry image = pages.get(pi);
                        String normalizedExt = canonicalExt(extension(image.getName()));
                        String imageName = String.format(Locale.ROOT, "c%04d_p%06d.%s", ci+1, pi+1, normalizedExt);
                        writeZipImage(z, inputZip, image, imageName);
                        manifest.append("<item id=\"img-").append(ci+1).append('-').append(pi+1)
                                .append("\" href=\"Images/").append(imageName)
                                .append("\" media-type=\"").append(mime(normalizedExt)).append("\"/>");
                        chapterImages.add(imageName);
                        if (!scroll) {
                            String pageName = String.format(Locale.ROOT, "c%04d_p%06d.xhtml", ci+1, pi+1);
                            String pageId = "pg-" + (ci+1) + "-" + (pi+1);
                            addText(z, "Text/" + pageName, wrapPage(imageName, false, bgPath != null));
                            manifest.append("<item id=\"").append(pageId).append("\" href=\"Text/")
                                    .append(pageName).append("\" media-type=\"application/xhtml+xml\"/>");
                            spine.append("<itemref idref=\"").append(pageId).append("\"/>");
                            if (pi == 0) firstHref = "Text/" + pageName;
                        }
                        actual++;
                        if (progress != null && (actual % 4 == 0 || actual == total))
                            progress.update(actual, total, "Đã thêm " + actual + "/" + total + " ảnh");
                    }
                    if (scroll) {
                        String xhtml = String.format(Locale.ROOT, "c%04d.xhtml", ci+1);
                        addText(z, "Text/" + xhtml, scrollChapter(chapterImages, chapter.title, bgPath != null));
                        String chapterId = "chapter-" + (ci+1);
                        manifest.append("<item id=\"").append(chapterId).append("\" href=\"Text/")
                                .append(xhtml).append("\" media-type=\"application/xhtml+xml\"/>");
                        spine.append("<itemref idref=\"").append(chapterId).append("\"/>");
                        firstHref = "Text/" + xhtml;
                    }
                    nav.append("<li><a href=\"").append(firstHref).append("\">")
                            .append(escape(chapter.title)).append("</a></li>");
                    ncx.append("<navPoint id=\"np-").append(ci+1).append("\" playOrder=\"")
                            .append(ci+1).append("\"><navLabel><text>").append(escape(chapter.title))
                            .append("</text></navLabel><content src=\"").append(firstHref)
                            .append("\"/></navPoint>");
                } finally {
                    if (!tmp.delete()) tmp.deleteOnExit();
                }
            }
            if (actual != expected) throw new IOException("Ảnh thiếu: " + actual + "/" + expected);
            addText(z, "nav.xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"vi\"><head><title>Mục lục</title></head>"
                    + "<body><nav xmlns:epub=\"http://www.idpf.org/2007/ops\" epub:type=\"toc\" id=\"toc\">"
                    + "<h1>Mục lục</h1><ol>" + nav + "</ol></nav></body></html>");
            addText(z, "toc.ncx", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<!DOCTYPE ncx PUBLIC \"-//NISO//DTD ncx 2005-1//EN\" \"http://www.daisy.org/z3986/2005/ncx-2005-1.dtd\">"
                    + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">"
                    + "<head><meta name=\"dtb:uid\" content=\"" + escape(uuid) + "\"/><meta name=\"dtb:depth\" content=\"1\"/>"
                    + "<meta name=\"dtb:totalPageCount\" content=\"0\"/><meta name=\"dtb:maxPageNumber\" content=\"0\"/></head>"
                    + "<docTitle><text>" + escape(title) + "</text></docTitle><navMap>" + ncx + "</navMap></ncx>");
            String modified = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC).format(Instant.now());
            addText(z, "content.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"book-id\" xml:lang=\"vi\">"
                    + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                    + "<dc:identifier id=\"book-id\">" + escape(uuid) + "</dc:identifier>"
                    + "<dc:title>" + escape(title) + "</dc:title>"
                    + "<dc:language>vi</dc:language><dc:creator>" + escape(author.isEmpty() ? "Không ghi" : author) + "</dc:creator>"
                    + "<meta property=\"dcterms:modified\">" + modified + "</meta>"
                    + "<meta property=\"rendition:layout\">" + (scroll ? "reflowable" : "pre-paginated") + "</meta>"
                    + "<meta name=\"cover\" content=\"cover-image\"/></metadata>"
                    + "<manifest>" + manifest + "</manifest><spine toc=\"toc-ncx\" page-progression-direction=\""
                    + (rtl ? "rtl" : "ltr") + "\">" + spine + "</spine></package>");
            z.finish();
            if (progress != null) progress.update(actual, total, "Hoàn tất: " + actual + " ảnh");
        }
    }
}
