package io.github.trekkerdrove.garminmcp.infrastructure.external.garmin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import com.garmin.fit.Decode;
import io.github.trekkerdrove.garminmcp.application.dto.FitRequest;
import io.github.trekkerdrove.garminmcp.application.dto.GarminFailure;
import io.github.trekkerdrove.garminmcp.application.port.out.FitDecoder;
import io.github.trekkerdrove.garminmcp.application.service.ReadFitActivity;
import org.springframework.stereotype.Component;

import static java.util.zip.ZipEntry.*;

@Component
final class GarminFitDecoder implements FitDecoder {
    private static final int MAX_EXPANDED = 8 * 1024 * 1024;
    private static final int MAX_ENTRIES = 8;
    private static final long MAX_NANOS = Duration.ofSeconds(5).toNanos();

    @Override
    public Decoded read(byte[] archive, FitRequest request) {
        long started = System.nanoTime();
        if (archive.length > ReadFitActivity.MAX_ARCHIVE_BYTES) {
            throw new GarminFailure("fit_limit_exceeded");
        }
        try {
            byte[] member = extract(archive, started);
            if (member == null) {
                throw new GarminFailure("no_fit_member");
            }
            if (member.length < 14 || member[8] != '.' || member[9] != 'F' || member[10] != 'I' || member[11] != 'T') {
                throw new GarminFailure("invalid_fit");
            }
            var projection = new FitProjection(request);
            var stream = new ByteArrayInputStream(member) {
                @Override
                public synchronized int read(byte[] bytes, int offset, int length) {
                    checkDeadline(started);
                    return super.read(bytes, offset, length);
                }
            };
            if (!new Decode().read(stream, projection::accept, null)) {
                throw new GarminFailure("invalid_fit");
            }
            checkDeadline(started);
            return projection.finish();
        } catch (GarminFailure failure) {
            throw failure;
        } catch (IOException | RuntimeException unreadable) {
            throw new GarminFailure("invalid_fit");
        }
    }

    private static byte[] extract(byte[] archive, long started) throws IOException {
        var bytes = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        var directory = directory(bytes);
        int local = 0;
        int expanded = 0;
        ByteArrayOutputStream member = null;
        byte[] buffer = new byte[8192];
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                Integer central = directory.headers.remove(local);
                if (central == null) throw new GarminFailure("invalid_fit");
                boolean fit = !entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".fit");
                if (fit) {
                    if (member != null) {
                        throw new GarminFailure("multiple_fit_members");
                    }
                    member = new ByteArrayOutputStream();
                }
                for (int count; (count = zip.read(buffer)) >= 0;) {
                    checkDeadline(started);
                    expanded += count;
                    if (expanded > MAX_EXPANDED) {
                        throw new GarminFailure("fit_limit_exceeded");
                    }
                    if (fit) {
                        member.write(buffer, 0, count);
                    }
                }
                int next = directory.headers.isEmpty() ? directory.start : directory.headers.firstKey();
                verifyMember(bytes, local, next, central, entry);
                local = next;
            }
        }
        if (!directory.headers.isEmpty() || local != directory.start) {
            throw new GarminFailure("invalid_fit");
        }
        return member == null ? null : member.toByteArray();
    }

    private record Directory(int start, TreeMap<Integer, Integer> headers) {}

    // ZipInputStream validates local member data but ignores the central directory entirely.
    private static Directory directory(ByteBuffer bytes) {
        int length = bytes.limit();
        int first = Math.max(0, length - ENDHDR - 65535);
        for (int i = length - ENDHDR; i >= first; i--) {
            if (bytes.getInt(i) != ENDSIG || i + ENDHDR + u16(bytes, i + ENDCOM) != length) {
                continue;
            }
            int entries = u16(bytes, i + ENDTOT);
            if (entries > MAX_ENTRIES) {
                throw new GarminFailure("fit_limit_exceeded");
            }
            int start = bytes.getInt(i + ENDOFF);
            if (bytes.getInt(i + 4) != 0 || entries != u16(bytes, i + ENDSUB) || start < 0
                    || start + u32(bytes, i + ENDSIZ) != i) {
                break;
            }
            var headers = new TreeMap<Integer, Integer>();
            int cursor = start;
            for (int count = 0; count < entries; count++) {
                if (cursor > i - CENHDR || bytes.getInt(cursor) != CENSIG || u16(bytes, cursor + CENDSK) != 0) {
                    throw new GarminFailure("invalid_fit");
                }
                int local = bytes.getInt(cursor + CENOFF);
                if (local < 0 || local > start - LOCHDR || headers.put(local, cursor) != null) {
                    throw new GarminFailure("invalid_fit");
                }
                cursor += CENHDR + u16(bytes, cursor + CENNAM) + u16(bytes, cursor + CENEXT) + u16(bytes, cursor + CENCOM);
            }
            if (cursor != i) break;
            return new Directory(start, headers);
        }
        throw new GarminFailure("invalid_fit");
    }

    private static void verifyMember(ByteBuffer bytes, int local, int next, int central, ZipEntry entry) {
        int flags = u16(bytes, local + LOCFLG);
        int nameLength = u16(bytes, local + LOCNAM);
        int name = local + LOCHDR;
        int centralName = central + CENHDR;
        if (bytes.getInt(local) != LOCSIG || flags != u16(bytes, central + CENFLG)
                || entry.getMethod() != u16(bytes, central + CENHOW) || nameLength != u16(bytes, central + CENNAM)
                || !Arrays.equals(bytes.array(), name, name + nameLength, bytes.array(), centralName, centralName + nameLength)
                || entry.getCrc() != u32(bytes, central + CENCRC) || entry.getSize() != u32(bytes, central + CENLEN)
                || entry.getCompressedSize() != u32(bytes, central + CENSIZ)) {
            throw new GarminFailure("invalid_fit");
        }
        long dataEnd = name + nameLength + u16(bytes, local + LOCEXT) + entry.getCompressedSize();
        long descriptor = next - dataEnd;
        // A streaming ZIP may include a data descriptor, with or without its four-byte signature.
        if ((flags & 8) == 0 ? descriptor != 0
                : descriptor != EXTHDR - 4 && !(descriptor == EXTHDR && bytes.getInt((int) dataEnd) == EXTSIG)) {
            throw new GarminFailure("invalid_fit");
        }
    }

    private static int u16(ByteBuffer bytes, int offset) {
        return Short.toUnsignedInt(bytes.getShort(offset));
    }

    private static long u32(ByteBuffer bytes, int offset) {
        return Integer.toUnsignedLong(bytes.getInt(offset));
    }

    private static void checkDeadline(long started) {
        if (System.nanoTime() - started > MAX_NANOS) {
            throw new GarminFailure("fit_limit_exceeded");
        }
    }
}
