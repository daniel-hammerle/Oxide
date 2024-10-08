package at.oxide.arenas;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.FieldLayout;

import java.util.Arrays;
import java.util.stream.Collectors;

public class ClassRepr {
    private final FieldRepr[] fields;
    private final long instanceSize;
    private final long headerSize;

    public ClassRepr(FieldRepr[] fields, long instanceSize, long headerSize) {
        this.fields = fields;
        this.instanceSize = instanceSize;
        this.headerSize = headerSize;
    }

    public static ClassRepr fromClassLayout(ClassLayout layout) {
        FieldRepr[] fields = new FieldRepr[layout.fields().size()];
        int i = 0;
        for (FieldLayout fl : layout.fields()) {
            fields[i] = FieldRepr.fromLayout(fl);
            i++;
        }
        return new ClassRepr(fields, layout.instanceSize(), layout.headerSize());
    }

    public FieldRepr[] fields() {
        return fields;
    }

    public long instanceSize() {
        return instanceSize;
    }

    public long headerSize() {
        return headerSize;
    }

    @Override
    public String toString() {
        return "ClassRepr{" +
                "fields=" + Arrays.toString(fields) +
                ", instanceSize=" + instanceSize +
                ", headerSize=" + headerSize +
                '}';
    }
}
