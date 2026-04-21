package exceptions;

public class IllegalWriteException extends RuntimeException {
    public IllegalWriteException() {
        super();
    }

    public IllegalWriteException(String msg) {
        super(msg);
    }
}
