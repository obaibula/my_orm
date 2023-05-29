package orm;

import orm.annotation.Column;
import orm.annotation.Id;
import orm.annotation.Table;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import static java.util.Comparator.comparing;

// todo: write java doc and comments
@RequiredArgsConstructor
public class Session {
    private final DataSource dataSource;
    private final Map<EntityKey<?>, Object> cache = new HashMap<>();
    private final Map<EntityKey<?>, List<FieldKey>> entitiesSnapshotMap = new HashMap<>();

    private List<FieldKey> fieldKeys;

    /**
     * Basic implementation of find(). This implementation contains cache.
     *
     * @param type
     * @param id
     * @param <T>
     * @return T
     */
    public <T> T find(Class<T> type, Object id) {
        var key = new EntityKey<>(type, id);
        entitiesSnapshotMap.put(key, new ArrayList<>());
        fieldKeys = entitiesSnapshotMap.get(key);
        var entity = cache.computeIfAbsent(key, this::loadFromDB);
        return type.cast(entity);
    }

    private <T> T loadFromDB(EntityKey<T> entityKey) {
        var type = entityKey.type();
        var id = entityKey.id();
        try (Connection connection = dataSource.getConnection()) {
            String sql = prepareFindSqlQuery(type);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setObject(1, id);
                System.out.println("SQL:**************\n" + sql + "******************");
                var resultSet = statement.executeQuery();

                return createEntityFromResultSet(type, id, resultSet, sql);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private <T> T createEntityFromResultSet(Class<T> type, Object id, ResultSet resultSet, String sql) {
        try {
            resultSet.next();
            T typeInstance = getInstance(type);

            //set the id field
            setIdField(type, id, typeInstance);

            //set the @Column annotated fields
            var annotatedFields = getAnnotatedWithColumnFields(type);
            for (var field : annotatedFields) {
                initializeField(resultSet, typeInstance, field);
            }
            return type.cast(typeInstance);
        } catch (SQLException |
                 NoSuchFieldException |
                 IllegalAccessException e) {
            throw new RuntimeException(e);
        }

    }

    private <T> void initializeField(ResultSet resultSet, T typeInstance, Field field)
            throws IllegalAccessException, SQLException {

        String name = field.getAnnotation(Column.class).name();
        Object value = resultSet.getObject(name);
        field.set(typeInstance, value);
        fieldKeys.add(new FieldKey(field, value));
    }

    private <T> void setIdField(Class<T> type, Object id, T typeInstance)
            throws NoSuchFieldException, IllegalAccessException {

        var idField = type.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(typeInstance, id);
    }

    private <T> List<Field> getAnnotatedWithColumnFields(Class<T> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Column.class))
                .peek(field -> field.setAccessible(true))
                .sorted(comparing(Field::getName))
                .toList();
    }


    private <T> T getInstance(Class<T> type) {
        try {
            return type.getConstructor().newInstance();
        } catch (InstantiationException |
                 IllegalAccessException |
                 InvocationTargetException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }


    //id annotation must be very single!!!
    private Map<Field, String> getSingleElementMap(Field[] fields) {
        var annotatedField = Arrays.stream(fields)
                .filter(field -> field.isAnnotationPresent(Id.class))
                .findAny()
                .orElseThrow();

        return Collections.singletonMap(annotatedField,
                annotatedField.getAnnotation(Id.class).name());
    }

    /**
     * close() method, which also performs dirty checking
     */
    public void close() {
            for (var cacheEntry : cache.entrySet()) {

                //get an array of snapshots values with the corresponding cache keys
                var attributes = entitiesSnapshotMap.get(cacheEntry.getKey());

                //get all fields annotated with @Column from current Entity from cache
                var cacheEntity = cacheEntry.getValue();
                var annotatedFields = getAnnotatedWithColumnFields(cacheEntity.getClass());

                compareValues(cacheEntity, attributes, annotatedFields);
            }

    }

    @SneakyThrows
    private void compareValues(Object cacheEntity,
                               List<FieldKey> attributes,
                               List<Field> annotatedFields) {

        outer:
        for (var fieldKey : attributes) {
            for (var annotatedField : annotatedFields) {

                String cachedFieldName = annotatedField.getName();
                String snapshotFieldName = fieldKey.field().getName();
                if (cachedFieldName.equals(snapshotFieldName)) {
                    Object cachedValue = null;
                        cachedValue = annotatedField.get(cacheEntity);
                    Object snapshotValue = fieldKey.value();

                    if (!cachedValue.equals(snapshotValue)) {
                        // we need our cachedEntity, to get access to the updated fields later
                        update(cacheEntity);
                        // even if only one field has changed in the snapshot version
                        // we are to perform full update of the entity, to avoid multiple updates
                        break outer;
                    }
                }
            }
        }
    }

    private void update(Object cacheEntity) {
        try (var connection = dataSource.getConnection()) {
            String sql = prepareUpdateSqlQuery(cacheEntity);
            System.out.println("SQL:**************");
            System.out.println(sql);
            System.out.println("******************");
            try (var statement = connection.prepareStatement(sql)) {
                prepareStatementForUpdate(cacheEntity, statement);
                statement.executeUpdate();
            }
        } catch (SQLException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private void prepareStatementForUpdate(Object cacheEntity, PreparedStatement statement)
            throws IllegalAccessException, SQLException {

        Class<?> type = cacheEntity.getClass();
        List<Field> annotatedFields = getAnnotatedWithColumnFields(type);
        int size = annotatedFields.size();

        //set the @Column fields in SET clause
        for (int i = 1; i <= size; i++) {
            Object snapshotValue = annotatedFields.get(i - 1).get(cacheEntity);
            statement.setObject(i, snapshotValue);
        }

        //set the @Id field in WHERE clause
        statement.setObject(size + 1, getIdField(type).get(cacheEntity));
    }

    private Field getIdField(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Id.class))
                .peek(field -> field.setAccessible(true))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Field with id annotation is not found"));
    }

    private <T> String prepareFindSqlQuery(Class<T> type) {
        String tableName = getTableNameFromAnnotationTable(type);
        String idFieldName = getFieldNameFromAnnotationId(type);

        return String.format("""
                SELECT *
                FROM %s
                WHERE %s = ?
                """, tableName, idFieldName);
    }

    private String prepareUpdateSqlQuery(Object cacheEntity) {
        Class<?> type = cacheEntity.getClass();
        String tableName = getTableNameFromAnnotationTable(type);
        String idFieldName = getFieldNameFromAnnotationId(type);

        StringBuilder sqlBuilder = buildUpdateSqlString(type);

        return String.format(sqlBuilder.toString(), tableName, idFieldName);
    }

    private StringBuilder buildUpdateSqlString(Class<?> type) {
        StringBuilder sqlBuilder = new StringBuilder(
                """
                        UPDATE %s
                        SET
                        """);

        var columnNames = getColumnNames(type);
        columnNames.forEach(columnName -> {
            sqlBuilder.append(columnName)
                    .append(" = ?, ");
        });

        sqlBuilder.deleteCharAt(sqlBuilder.length() - 2)
                .deleteCharAt(sqlBuilder.length() - 1)
                .append("\nWHERE %s = ?");

        return sqlBuilder;
    }

    private List<String> getColumnNames(Class<?> type) {
        return getAnnotatedWithColumnFields(type)
                .stream()
                .map(field -> field.getAnnotation(Column.class).name())
                .toList();
    }

    private <T> String getTableNameFromAnnotationTable(Class<T> type) {
        var annotation = type.getDeclaredAnnotation(Table.class);
        String tableName;
        String annotationTableValue = annotation.value();
        if (annotationTableValue.isEmpty()) {
            tableName = type.getSimpleName().toLowerCase() + "s";
        } else tableName = annotationTableValue;
        return tableName;
    }

    private <T> String getFieldNameFromAnnotationId(Class<T> type) {
        String fieldName = "";
        var fields = type.getDeclaredFields();
        var fieldIdAnnotationMap = getSingleElementMap(fields);

        for (var entry : fieldIdAnnotationMap.entrySet()) {
            if (entry.getValue().isEmpty()) {
                fieldName = entry.getKey().getName();
            } else fieldName = entry.getValue();
        }
        return fieldName;
    }
}