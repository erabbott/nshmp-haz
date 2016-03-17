package org.opensha2.programs;

import static java.lang.Runtime.getRuntime;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.opensha2.util.TextUtils.NEWLINE;

import java.io.IOException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import org.opensha2.calc.CalcConfig;
import org.opensha2.calc.Calcs;
import org.opensha2.calc.Hazard;
import org.opensha2.calc.Results;
import org.opensha2.calc.Site;
import org.opensha2.calc.Sites;
import org.opensha2.eq.model.HazardModel;
import org.opensha2.util.Logging;

import com.google.common.base.Optional;
import com.google.common.base.StandardSystemProperty;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;

/**
 * Compute probabilisitic seismic hazard at a {@link Site} from a
 * {@link HazardModel}.
 * 
 * @author Peter Powers
 */
public class HazardCalc {

	// TODO move to config
	private static final int FLUSH_LIMIT = 5;

	/**
	 * Entry point for a hazard calculation.
	 * 
	 * <p>Computing hazard curves requires at least 2, and at most 3, arguments.
	 * At a minimum, the path to a model zip file or directory and the site(s)
	 * at which to perform calculations must be specified. Under the 2-argument
	 * scenario, model initialization and calculation configuration settings are
	 * drawn from the config file that <i>must</i> reside at the root of the
	 * model directory. Sites may be defined as a string, a CSV file, or a
	 * GeoJSON file.
	 * 
	 * <p>To override any default or calculation configuration settings included
	 * with the model, supply the path to another configuration file as a third
	 * argument.
	 * 
	 * <p>Please refer to the nshmp-haz <a
	 * href="https://github.com/usgs/nshmp-haz/wiki">wiki</a> for comprehensive
	 * descriptions of source models, configuration files, site files, and
	 * hazard calculations.</p>
	 * 
	 * @see <a href="https://github.com/usgs/nshmp-haz/wiki/Building-&-Running">
	 *      nshmp-haz wiki</a>
	 * @see <a
	 *      href="https://github.com/usgs/nshmp-haz/tree/master/etc/examples">
	 *      example calculations</a>
	 */
	public static void main(String[] args) {

		/* Delegate to run which has a return value for testing. */

		Optional<String> status = run(args);
		if (status.isPresent()) {
			System.err.print(status.get());
			System.exit(1);
		}
		System.exit(0);
	}

	static Optional<String> run(String[] args) {
		int argCount = args.length;

		if (argCount < 2 || argCount > 3) {
			return Optional.of(USAGE);
		}

		Logging.init();
		Logger log = Logger.getLogger(HazardCalc.class.getName());

		try {
			log.info(PROGRAM + ": initializing...");
			Path modelPath = Paths.get(args[0]);
			HazardModel model = HazardModel.load(modelPath);

			CalcConfig config = model.config();
			Path out = Paths.get(StandardSystemProperty.USER_DIR.value());
			if (argCount == 3) {
				Path userConfigPath = Paths.get(args[2]);
				config = CalcConfig.builder()
					.copy(model.config())
					.extend(CalcConfig.builder(userConfigPath))
					.build();
				out = userConfigPath.getParent();
			}
			log.info(config.toString());

			Iterable<Site> sites = readSites(args[1]);
			log.info("");
			log.info("Sites:" + sites);

			calc(model, config, sites, out, log);
			log.info(PROGRAM + ": finished");
			return Optional.absent();

		} catch (Exception e) {
			StringBuilder sb = new StringBuilder()
				.append(NEWLINE)
				.append(PROGRAM + ": error").append(NEWLINE)
				.append(" Arguments: ").append(Arrays.toString(args)).append(NEWLINE)
				.append(NEWLINE)
				.append(Throwables.getStackTraceAsString(e))
				.append(USAGE);
			return Optional.of(sb.toString());
		}
	}

	static Iterable<Site> readSites(String arg) {
		try {
			if (arg.toLowerCase().endsWith(".csv")) {
				return Sites.fromCsv(Paths.get(arg));
			}
			if (arg.toLowerCase().endsWith(".geojson")) {
				return Sites.fromJson(Paths.get(arg));
			}
			return Sites.fromString(arg);
		} catch (Exception e) {
			throw new IllegalArgumentException(
				"'sites' [" + arg + "] must either be a 3 to 7 argument, comma-delimited string " +
					"or specify a path to a *.csv or *.geojson file", e);
		}
	}

	private static final OpenOption[] WRITE_OPTIONS = new OpenOption[] {};
	private static final OpenOption[] APPEND_OPTIONS = new OpenOption[] { APPEND };

	/*
	 * Compute hazard curves using the supplied model, config, and sites.
	 */
	private static void calc(
			HazardModel model,
			CalcConfig config,
			Iterable<Site> sites,
			Path out,
			Logger log) throws IOException {

		ExecutorService execSvc = createExecutor();
		Optional<Executor> executor = Optional.<Executor> of(execSvc);

		log.info(PROGRAM + ": calculating ...");
		Stopwatch batchWatch = Stopwatch.createStarted();
		Stopwatch totalWatch = Stopwatch.createStarted();
		int count = 0;

		List<Hazard> results = new ArrayList<>();
		boolean firstBatch = true;

		for (Site site : sites) {
			Hazard result = calc(model, config, site, executor);
			results.add(result);
			if (results.size() == FLUSH_LIMIT) {
				OpenOption[] opts = firstBatch ? WRITE_OPTIONS : APPEND_OPTIONS;
				firstBatch = false;
				Results.writeResults(out, results, opts);
				log.info("     batch: " + (count + 1) + "  " + batchWatch +
					"  total: " + totalWatch);
				results.clear();
				batchWatch.reset().start();
			}
			count++;
		}
		// write final batch
		if (!results.isEmpty()) {
			OpenOption[] opts = firstBatch ? WRITE_OPTIONS : APPEND_OPTIONS;
			Results.writeResults(out, results, opts);
		}
		log.info(PROGRAM + ": " + count + " complete " + totalWatch);

		execSvc.shutdown();
	}

	/**
	 * Compute hazard curves at a {@code site} for a {@code model} and
	 * {@code config}. If an {@code executor} is supplied, it will be used to
	 * distribute tasks; otherwise, one will be created.
	 * 
	 * <p><b>Note:</b> any model initialization settings in {@code config} will
	 * be ignored as the supplied model will already have been initialized.</p>
	 * 
	 * @param model to use
	 * @param config calculation configuration
	 * @param site of interest
	 * @param executor to use ({@link Optional})
	 * @return a HazardResult
	 */
	public static Hazard calc(
			HazardModel model,
			CalcConfig config,
			Site site,
			Optional<Executor> executor) {

		Optional<Executor> execLocal = executor.or(Optional.of(createExecutor()));

		try {
			Hazard result = Calcs.hazard(model, config, site, execLocal);
			// Shut down the locally created executor if none was supplied
			if (!executor.isPresent()) ((ExecutorService) execLocal.get()).shutdown();
			return result;
		} catch (ExecutionException | InterruptedException e) {
			Throwables.propagate(e);
			return null;
		}
	}

	private static ExecutorService createExecutor() {
		return newFixedThreadPool(getRuntime().availableProcessors());
	}

	private static final String PROGRAM = HazardCalc.class.getSimpleName();
	private static final String USAGE_COMMAND =
		"java -cp nshmp-haz.jar org.opensha2.programs.HazardCalc model sites [config]";
	private static final String USAGE_URL1 = "https://github.com/usgs/nshmp-haz/wiki";
	private static final String USAGE_URL2 = "https://github.com/usgs/nshmp-haz/tree/master/etc";
	private static final String SITE_STRING = "name,lon,lat[,vs30,vsInf[,z1p0,z2p5]]";

	private static final String USAGE = new StringBuilder()
		.append(NEWLINE)
		.append(PROGRAM).append(" usage:").append(NEWLINE)
		.append("  ").append(USAGE_COMMAND).append(NEWLINE)
		.append(NEWLINE)
		.append("Where:").append(NEWLINE)
		.append("  'model' is a model zip file or directory")
		.append(NEWLINE)
		.append("  'sites' is either:")
		.append(NEWLINE)
		.append("     - a string, e.g. ").append(SITE_STRING)
		.append(NEWLINE)
		.append("       (site class and basin terms are optional)")
		.append(NEWLINE)
		.append("       (escape any spaces or enclose string in double-quotes)")
		.append(NEWLINE)
		.append("     - or a *.csv file or *.geojson file of site data")
		.append(NEWLINE)
		.append("  'config' (optional) supplies a calculation configuration")
		.append(NEWLINE)
		.append(NEWLINE)
		.append("For more information, see:").append(NEWLINE)
		.append("  ").append(USAGE_URL1).append(NEWLINE)
		.append("  ").append(USAGE_URL2).append(NEWLINE)
		.append(NEWLINE)
		.toString();
}
