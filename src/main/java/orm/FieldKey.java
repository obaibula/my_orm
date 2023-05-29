package orm;

import java.lang.reflect.Field;

public record FieldKey(Field field, Object value) {
}
