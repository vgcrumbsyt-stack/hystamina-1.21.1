package hystamina;

public final class HyStaminaRuntimeSettings {
	private static volatile double sprintSpeedModifier = 0.24728171774768358D;

	private HyStaminaRuntimeSettings() {
	}

	public static double getSprintSpeedModifier() {
		return sprintSpeedModifier;
	}

	public static void setSprintSpeedModifier(double value) {
		sprintSpeedModifier = value;
	}
}