import java.util.jar.JarFile;

/** CI helper: checks whether an android.jar contains the camera2 API. */
public class JarCheck {
    public static void main(String[] a) throws Exception {
        try (JarFile jf = new JarFile(a[0])) {
            boolean has = jf.getJarEntry("android/hardware/camera2/CameraCaptureRequest.class") != null;
            System.out.println(has ? "1" : "0");
            if (a.length > 1 && a[1].equals("dump")) {
                int n = 0;
                java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
                while (en.hasMoreElements() && n < 12) {
                    java.util.jar.JarEntry e = en.nextElement();
                    if (e.getName().contains("camera2") && e.getName().endsWith(".class")) {
                        System.out.println("ENTRY " + e.getName() + " size=" + e.getSize());
                        n++;
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("ERR " + t);
        }
    }
}
