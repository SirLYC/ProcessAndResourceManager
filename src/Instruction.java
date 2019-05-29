import java.util.Random;

public enum Instruction {
    Computation, IO, Fork,
    Request, Release;

    private static final Random r = new Random();

    public void run() throws Exception {
        if (r.nextDouble() < 1e-5) {
            // p = 1e-5
            throw new Exception("emulate error: " + name());
        }
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            // do nothing
        }
    }
}
