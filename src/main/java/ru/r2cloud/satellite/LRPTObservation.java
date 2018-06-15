package ru.r2cloud.satellite;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.util.Date;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;

import ru.r2cloud.jradio.Context;
import ru.r2cloud.jradio.PhaseAmbiguityResolver;
import ru.r2cloud.jradio.blocks.AGC;
import ru.r2cloud.jradio.blocks.ClockRecoveryMMComplex;
import ru.r2cloud.jradio.blocks.Constellation;
import ru.r2cloud.jradio.blocks.ConstellationSoftDecoder;
import ru.r2cloud.jradio.blocks.CorrelateAccessCodeTag;
import ru.r2cloud.jradio.blocks.CostasLoop;
import ru.r2cloud.jradio.blocks.FixedLengthTagger;
import ru.r2cloud.jradio.blocks.FloatToChar;
import ru.r2cloud.jradio.blocks.LowPassFilter;
import ru.r2cloud.jradio.blocks.Rail;
import ru.r2cloud.jradio.blocks.RootRaisedCosineFilter;
import ru.r2cloud.jradio.blocks.TaggedStreamToPdu;
import ru.r2cloud.jradio.blocks.Window;
import ru.r2cloud.jradio.lrpt.LRPT;
import ru.r2cloud.jradio.meteor.MeteorImage;
import ru.r2cloud.jradio.source.WavFileSource;
import ru.r2cloud.jradio.util.Metrics;
import ru.r2cloud.model.ObservationResult;
import ru.r2cloud.model.SatPass;
import ru.r2cloud.model.Satellite;
import ru.r2cloud.util.Configuration;
import ru.r2cloud.util.ProcessFactory;
import ru.r2cloud.util.ProcessWrapper;
import ru.r2cloud.util.Util;

public class LRPTObservation implements Observation {

	private static final Logger LOG = LoggerFactory.getLogger(LRPTObservation.class);
	private static final float INPUT_SAMPLE_RATE = 1440000.0f;
	private static final float OUTPUT_SAMPLE_RATE = 150000.0f;
	private static final int BUF_SIZE = 0x1000; // 4K

	private ProcessWrapper rtlSdr = null;
	private File wavPath;

	private final MetricRegistry r2cloudRegistry = Metrics.getRegistry();
	private final Satellite satellite;
	private final Configuration config;
	private final SatPass nextPass;
	private final ProcessFactory factory;
	private final ObservationResultDao dao;
	private final String observationId;

	public LRPTObservation(Configuration config, Satellite satellite, SatPass nextPass, ProcessFactory factory, ObservationResultDao dao) {
		this.config = config;
		this.satellite = satellite;
		this.nextPass = nextPass;
		this.factory = factory;
		this.dao = dao;
		this.observationId = String.valueOf(nextPass.getStart().getTime().getTime());
	}

	@Override
	public void start() {
		try {
			this.wavPath = File.createTempFile(satellite.getId() + "-", ".wav");
		} catch (IOException e) {
			LOG.error("unable to create temp file", e);
			return;
		}
		ProcessWrapper sox = null;
		try {
			Integer ppm = config.getInteger("ppm.current");
			if (ppm == null) {
				ppm = 0;
			}
			sox = factory.create(config.getProperty("satellites.sox.path") + " --type raw --rate " + INPUT_SAMPLE_RATE + " --encoding unsigned-integer --bits 8 --channels 2 - " + wavPath.getAbsolutePath() + " rate " + OUTPUT_SAMPLE_RATE, Redirect.INHERIT, false);
			rtlSdr = factory.create(config.getProperty("satellites.rtlsdr.path") + " -f " + String.valueOf(satellite.getFrequency()) + " -s " + INPUT_SAMPLE_RATE + " -g 45 -p " + String.valueOf(ppm) + " - ", Redirect.INHERIT, false);
			byte[] buf = new byte[BUF_SIZE];
			while (!Thread.currentThread().isInterrupted()) {
				int r = rtlSdr.getInputStream().read(buf);
				if (r == -1) {
					break;
				}
				sox.getOutputStream().write(buf, 0, r);
			}
			sox.getOutputStream().flush();
		} catch (IOException e) {
			LOG.error("unable to run", e);
		} finally {
			LOG.info("stopping pipe thread");
			Util.shutdown("rtl_sdr for satellites", rtlSdr, 10000);
			Util.shutdown("sox", sox, 10000);
		}
	}

	@Override
	public void stop() {
		Util.shutdown("rtl_sdr for satellites", rtlSdr, 10000);
		rtlSdr = null;

		if (!wavPath.exists()) {
			LOG.info("nothing saved");
			return;
		}

		if (!dao.createObservation(satellite.getId(), observationId, wavPath)) {
			return;
		}

	}

	@Override
	public void decode() {
		ObservationResult cur = dao.find(satellite.getId(), observationId);
		if (cur == null) {
			return;
		}

		float symbolRate = 72000f;
		float clockAlpha = 0.010f;
		LRPT lrpt = null;
		try {
			WavFileSource source = new WavFileSource(new BufferedInputStream(new FileInputStream(cur.getWavPath())));
			LowPassFilter lowPass = new LowPassFilter(source, 1.0, OUTPUT_SAMPLE_RATE, 50000.0, 1000.0, Window.WIN_HAMMING, 6.76);
			AGC agc = new AGC(lowPass, 1000e-4f, 0.5f, 1.0f, 4000.0f);
			RootRaisedCosineFilter rrcf = new RootRaisedCosineFilter(agc, 1.0f, OUTPUT_SAMPLE_RATE, symbolRate, 0.6f, 361);

			CostasLoop costas = new CostasLoop(rrcf, 0.020f, 4, false);
			float omega = (float) ((OUTPUT_SAMPLE_RATE * 1.0) / (symbolRate * 1.0));
			ClockRecoveryMMComplex clockmm = new ClockRecoveryMMComplex(costas, omega, clockAlpha * clockAlpha / 4, 0.5f, clockAlpha, 0.005f);
			Constellation constel = new Constellation(new float[] { -1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f, 1.0f, -1.0f }, new int[] { 0, 1, 3, 2 }, 4, 1);
			ConstellationSoftDecoder constelDecoder = new ConstellationSoftDecoder(clockmm, constel);
			Rail rail = new Rail(constelDecoder, -1.0f, 1.0f);
			FloatToChar f2char = new FloatToChar(rail, 127.0f);

			PhaseAmbiguityResolver phaseAmbiguityResolver = new PhaseAmbiguityResolver(0x035d49c24ff2686bL);

			Context context = new Context();
			CorrelateAccessCodeTag correlate = new CorrelateAccessCodeTag(context, f2char, 12, phaseAmbiguityResolver.getSynchronizationMarkers(), true);
			TaggedStreamToPdu tag = new TaggedStreamToPdu(context, new FixedLengthTagger(context, correlate, 8160 * 2 + 8 * 2));
			lrpt = new LRPT(context, tag, phaseAmbiguityResolver);
			MeteorImage image = new MeteorImage(lrpt);
			BufferedImage actual = image.toBufferedImage();
			if (actual != null) {
				File imageFile = File.createTempFile(satellite.getId() + "-", ".jpg");
				ImageIO.write(actual, "jpg", imageFile);
				dao.saveChannel(satellite.getId(), observationId, imageFile, "a");
			}
		} catch (Exception e) {
			LOG.error("unable to process: " + cur.getWavPath(), e);
		} finally {
			if (lrpt != null) {
				try {
					lrpt.close();
				} catch (IOException e) {
					LOG.info("unable to close", e);
				}
			}
		}

		Counter numberOfDecodedPackets = r2cloudRegistry.counter(LRPT.class.getName());

		cur.setStart(nextPass.getStart().getTime());
		cur.setEnd(nextPass.getEnd().getTime());
		cur.setNumberOfDecodedPackets(numberOfDecodedPackets.getCount());
		dao.saveMeta(satellite.getId(), cur);

		// reset counter
		numberOfDecodedPackets.dec(numberOfDecodedPackets.getCount());
	}

	@Override
	public Date getStart() {
		return nextPass.getStart().getTime();
	}

	@Override
	public Date getEnd() {
		return nextPass.getEnd().getTime();
	}

	@Override
	public String getId() {
		return observationId;
	}

}
