package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.Dataset;
import jakarta.enterprise.inject.spi.CDI;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Entry points called from Dataverse commands (one line per hook). Each method:
 * <ul>
 * <li>does nothing unless {@code dataverse.dark.enabled=true}</li>
 * <li>ignores harvested datasets;</li>
 * <li>never throws, so a dARK problem can never break the command.</li>
 * </ul>
 * The service is looked up through CDI so that {@code CommandContext} does not need to change.
 */
public final class DarkHooks {

    private static final Logger logger = Logger.getLogger(DarkHooks.class.getCanonicalName());

    private DarkHooks() {
    }

    /** {@code AbstractCreateDatasetCommand.onSuccess}: reserve an ARK. */
    public static void datasetCreated(Dataset dataset) {
        if (isLocal(dataset)) {
            call("created", s -> s.onDatasetCreated(dataset.getId()));
        }
    }

    /**
     * {@code FinalizeDatasetPublicationCommand.execute}, before the dataset is merged:
     * write the Other ID from the local table (database only, no HTTP).
     */
    public static void beforePublication(Dataset dataset) {
        if (isLocal(dataset)) {
            call("beforePublication", s -> s.addOtherIdForPublication(dataset));
        }
    }

    /** {@code FinalizeDatasetPublicationCommand.onSuccess}: send the metadata. */
    public static void datasetPublished(Dataset dataset) {
        if (isLocal(dataset)) {
            call("published", s -> s.onDatasetPublished(dataset.getId(), false));
        }
    }

    /** {@code CuratePublishedDatasetVersionCommand.onSuccess} ({@code updatecurrent}): resend the metadata. */
    public static void datasetCurated(Dataset dataset) {
        if (isLocal(dataset)) {
            call("curated", s -> s.onDatasetPublished(dataset.getId(), true));
        }
    }

    /**
     * {@code DestroyDatasetCommand.onSuccess}: tombstone. Also covers deleting a
     * never-published dataset, which Dataverse implements as a destroy. Deaccessioning
     * never tombstones: a deaccessioned dataset can be published again.
     */
    public static void datasetDestroyed(Dataset dataset) {
        if (dataset == null || dataset.isHarvested() || !enabled()) {
            return;
        }
        String clientItemId = DarkIds.clientItemId(dataset);
        if (clientItemId != null) {
            call("destroyed", s -> s.onDatasetDestroyed(clientItemId));
        }
    }

    private static boolean isLocal(Dataset dataset) {
        return dataset != null && dataset.getId() != null && !dataset.isHarvested() && enabled();
    }

    /** Enabled and fully configured; checked without calling the EJB. */
    private static boolean enabled() {
        try {
            return DarkConfig.fromSettings().isUsable();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The service methods used here never throw (a system exception thrown by an EJB
     * joined to the command's transaction would mark it for rollback).
     */
    private static void call(String hook, Consumer<DarkServiceBean> action) {
        try {
            action.accept(CDI.current().select(DarkServiceBean.class).get());
        } catch (Exception e) {
            logger.log(Level.WARNING, "dARK hook '" + hook + "' failed; the operation itself was not affected", e);
        }
    }
}
