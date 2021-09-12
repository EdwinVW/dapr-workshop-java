package dapr.fines.violation;

import dapr.fines.fines.EmailGenerator;
import dapr.fines.fines.FineCalculator;
import dapr.fines.vehicle.VehicleInfo;
import dapr.fines.vehicle.VehicleRegistrationClient;
import io.dapr.client.DaprClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Map;

@Component
public class ViolationProcessor {
    private static final Logger log = LoggerFactory.getLogger(ViolationProcessor.class);

    private final DaprClient daprClient;
    private final EmailGenerator emailGenerator;
    private final FineCalculator fineCalculator;
    private final VehicleRegistrationClient vehicleRegistrationClient;

    public ViolationProcessor(final DaprClient daprClient,
                              final EmailGenerator emailGenerator,
                              final FineCalculator fineCalculator,
                              final VehicleRegistrationClient vehicleRegistrationClient) {
        this.daprClient = daprClient;
        this.emailGenerator = emailGenerator;
        this.fineCalculator = fineCalculator;
        this.vehicleRegistrationClient = vehicleRegistrationClient;
    }

    public void processSpeedingViolation(final SpeedingViolation violation) {
        var fine = fineCalculator.calculateFine(violation.excessSpeed());
        var fineText = fine == -1 ? "to be decided by the prosecutor" : String.format("EUR %.2f", (float) fine);
        var vehicleInfo = vehicleRegistrationClient.getVehicleInfo(violation.licenseNumber());

        // Send notification of fine by email
        var body = emailGenerator.createEmailBody(violation, vehicleInfo, fineText);
        var metadata = Map.of(
                "emailFrom", "noreply@cfca.gov",
                "emailTo", vehicleInfo.ownerEmail(),
                "subject", String.format("Speeding violation on the %s", violation.roadId())
        );

        daprClient.invokeBinding("sendmail", "create", body, metadata, Void.class)
                .block();

        log.info(constructLogMessage(violation, vehicleInfo, fineText));
    }

    private final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("LLLL, dd y");
    private final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private String constructLogMessage(final SpeedingViolation violation, final VehicleInfo vehicleInfo, final String fineText) {
        var date = DATE_FORMAT.format(violation.timestamp());
        var time = TIME_FORMAT.format(violation.timestamp());

        return String.format("""
                        Sent fine notification
                        \t\t\tTo %s, registered owner of license number %s.
                        \t\t\tViolation of %d km/h detected on the %s road on %s at %s.
                        \t\t\tFine: %s.%n
                        """,
                vehicleInfo.ownerName(),
                violation.licenseNumber(),
                violation.excessSpeed(),
                violation.roadId(),
                date,
                time,
                fineText
        );
    }

}
