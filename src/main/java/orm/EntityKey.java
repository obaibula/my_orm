package orm;

public record EntityKey<T>(Class<T> type, Object id) {
}
