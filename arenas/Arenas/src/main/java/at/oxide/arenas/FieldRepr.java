package at.oxide.arenas;

import org.openjdk.jol.info.FieldLayout;

import java.lang.reflect.Field;

import static at.oxide.arenas.Arenas.getFieldOffset;
import static at.oxide.arenas.Arenas.sanitizeClassName;

public class FieldRepr {
    private final String type;
    private final String name;
    private final long offset;

    public FieldRepr(String type, String name, long offset) {
        this.type = type;
        this.name = name;
        this.offset = offset;
    }

    public static FieldRepr fromField(Field field) {
        return new FieldRepr(
                sanitizeClassName(field.getType().getName()),
                field.getName(),
                getFieldOffset(field)
        );
    }

    public static FieldRepr fromLayout(FieldLayout layout) {
        return new FieldRepr(
                sanitizeClassName(layout.typeClass()),
                layout.name(),
                layout.offset()
        );
    }

    @Override
    public String toString() {
        return "FieldRepr{" +
                "type='" + type + '\'' +
                ", name='" + name + '\'' +
                ", offset=" + offset +
                '}';
    }

    public String name() {
        return name;
    }

    public String type() {
        return type;
    }

    public long offset() {
        return offset;
    }
}