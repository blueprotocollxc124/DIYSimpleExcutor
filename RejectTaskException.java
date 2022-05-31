package exception;

/*
 *@Author  LXC BlueProtocol
 *@Since   2022/5/19
 */


public class RejectTaskException extends Exception {
    public RejectTaskException() {
        super();
    }

    public RejectTaskException(String msg) {
        super(msg);
    }
}
