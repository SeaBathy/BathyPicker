import com.seabathy.bathypicker.core.Builder;
import com.seabathy.bathypicker.core.Catalog;
import com.seabathy.bathypicker.core.Job;
import com.seabathy.bathypicker.core.Planner;
import com.seabathy.bathypicker.core.TileSource;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the phone's build engine on a desktop JVM, for checking it against gdalwarp.
 * java -cp build/test DesktopBuild W S E N RES CRS RESAMPLING ZONES|all OUT.tif
 * Prints the gdalwarp grid it chose and the result.
 */
public class DesktopBuild {
    public static void main(String[] a) throws Exception {
        try { run(a); } catch (Throwable t) { t.printStackTrace(); System.exit(2); }
        System.exit(0);
    }

    static void run(String[] a) throws Exception {
        if (System.getProperty("ovr") != null) Builder.forceLevel = Integer.parseInt(System.getProperty("ovr"));
        double[] bbox = {Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3])};
        List<String> zones = a[7].equals("all") ? null : Arrays.asList(a[7].split(","));
        ExecutorService net = Executors.newFixedThreadPool(8);
        ExecutorService work = Executors.newFixedThreadPool(4);
        Catalog cat = new Catalog(net);
        Job job = new Job();
        Planner.Params p = Planner.parse(bbox, zones, a[5], a[4], a[6]);
        Planner.Plan plan = Planner.assess(p, cat, 3.0, new File(a[8]).getAbsoluteFile().getParentFile().getUsableSpace(), "here", job);
        System.out.println("assess: " + plan.json);
        if (!(Boolean) plan.json.get("ok")) System.exit(1);
        job.start();
        TileSource src = new TileSource(net, Math.max(48L << 20, Runtime.getRuntime().maxMemory() / 3));
        Builder b = new Builder(plan, job, src, work);
        long t0 = System.currentTimeMillis();
        new File(a[8]).delete();
        try (RandomAccessFile raf = new RandomAccessFile(a[8], "rw")) {
            FileChannel ch = raf.getChannel();
            Thread mon = new Thread(() -> {
                while (job.running()) {
                    System.out.printf("  %.0f%% %s%n", job.percent(), job.stage());
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                }
            });
            mon.setDaemon(true);
            mon.start();
            Map<String, Object> r = b.run(ch, ch);
            job.done(r);
            System.out.println("result: " + r);
        }
        System.out.println("log: " + job.snapshot().get("log"));
        System.out.printf("seconds: %.1f%n", (System.currentTimeMillis() - t0) / 1000.0);
        net.shutdownNow();
        work.shutdownNow();
    }
}
