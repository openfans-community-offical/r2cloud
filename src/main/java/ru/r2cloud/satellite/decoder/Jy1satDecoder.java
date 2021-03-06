package ru.r2cloud.satellite.decoder;

import ru.r2cloud.jradio.Beacon;
import ru.r2cloud.jradio.BeaconSource;
import ru.r2cloud.jradio.FloatInput;
import ru.r2cloud.jradio.demod.BpskDemodulator;
import ru.r2cloud.jradio.jy1sat.Jy1sat;
import ru.r2cloud.jradio.jy1sat.Jy1satBeacon;
import ru.r2cloud.model.ObservationRequest;
import ru.r2cloud.util.Configuration;

public class Jy1satDecoder extends TelemetryDecoder {

	public Jy1satDecoder(Configuration config) {
		super(config);
	}

	@Override
	public BeaconSource<? extends Beacon> createBeaconSource(FloatInput source, ObservationRequest req) {
		BpskDemodulator bpsk = new BpskDemodulator(source, 1200, 5, 0.0, 2000.0f, true);
		return new Jy1sat(bpsk);
	}
	
	@Override
	public Class<? extends Beacon> getBeaconClass() {
		return Jy1satBeacon.class;
	}
}
