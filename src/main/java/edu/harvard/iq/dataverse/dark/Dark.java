package edu.harvard.iq.dataverse.dark;

import edu.harvard.iq.dataverse.Dataset;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.sql.Timestamp;

/**
 * Local record of the dARK identifier assigned to a dataset. This table is the local
 * source of truth; the Other ID field of the dataset is only its public exposure.
 * The row survives the dataset: {@code dataset_id} is set to {@code NULL} on destroy.
 */
@Entity
@Table(name = "dark")
@NamedQueries({
    @NamedQuery(name = "Dark.findByDatasetId",
            query = "SELECT d FROM Dark d WHERE d.dataset.id = :datasetId"),
    @NamedQuery(name = "Dark.findByArk",
            query = "SELECT d FROM Dark d WHERE d.ark = :ark"),
    @NamedQuery(name = "Dark.findByClientItemId",
            query = "SELECT d FROM Dark d WHERE d.clientItemId = :clientItemId ORDER BY d.createdAt DESC"),
    @NamedQuery(name = "Dark.findByStatus",
            query = "SELECT d FROM Dark d WHERE d.status = :status ORDER BY d.updatedAt ASC"),
    @NamedQuery(name = "Dark.countByStatus",
            query = "SELECT d.status, COUNT(d) FROM Dark d GROUP BY d.status")
})
public class Dark implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The foreign key is owned by Flyway ({@code ON DELETE SET NULL}, so destroying a
     * dataset keeps the row); EclipseLink must not generate its own restrictive one.
     */
    @OneToOne
    @JoinColumn(name = "dataset_id", unique = true, nullable = true,
            foreignKey = @ForeignKey(value = ConstraintMode.NO_CONSTRAINT))
    private Dataset dataset;

    /** Canonical compact form, e.g. {@code ark:12345/xxxx}. */
    @Column(unique = true, length = 256)
    private String ark;

    /** Optimistic protection for short, concurrent local state transitions. */
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "sent_dataset_version_id")
    private Long sentDatasetVersionId;

    @Column(name = "sent_revision")
    private Long sentRevision;

    /** Durable intent, independent of errors and the current remote state. */
    @Column(name = "tombstone_requested", nullable = false)
    private boolean tombstoneRequested;

    @Column(name = "was_published", nullable = false)
    private boolean wasPublished;

    @Column(name = "reservation_token", length = 36)
    private String reservationToken;

    @Column(name = "reservation_lease_until")
    private Timestamp reservationLeaseUntil;



    @Convert(converter = DarkState.DbConverter.class)
    @Column(nullable = false, length = 1, columnDefinition = "CHAR(1)")
    private DarkState status;

    /** Normalized DOI of the dataset, as sent to the Minter. */
    @Column(name = "client_item_id", nullable = false, length = 100)
    private String clientItemId;

    @Column(length = 1024)
    private String target;

    /** Published version (e.g. {@code 2.1}) whose metadata was sent in the last accepted PUT. */
    @Column(name = "sent_version", length = 32)
    private String sentVersion;

    @Column(name = "metadata_cid", length = 128)
    private String metadataCid;

    @Column(name = "level1_cid", length = 128)
    private String level1Cid;

    @Column(name = "level2_cid", length = 128)
    private String level2Cid;

    /** Status checks performed without reaching {@link DarkState#PUBLISHED}. */
    @Column(nullable = false)
    private int attempts;

    /** Consecutive automatic {@code updatecurrent} failures adding the Other ID (see DarkPolicy). */
    @Column(name = "other_id_attempts", nullable = false)
    private int otherIdAttempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    @PrePersist
    void onCreate() {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Dataset getDataset() {
        return dataset;
    }

    public void setDataset(Dataset dataset) {
        this.dataset = dataset;
    }

    public String getArk() {
        return ark;
    }

    public void setArk(String ark) {
        this.ark = ark;
    }

    public DarkState getStatus() {
        return status;
    }

    public void setStatus(DarkState status) {
        this.status = status;
    }

    public String getClientItemId() {
        return clientItemId;
    }

    public void setClientItemId(String clientItemId) {
        this.clientItemId = clientItemId;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getSentVersion() {
        return sentVersion;
    }

    public void setSentVersion(String sentVersion) {
        this.sentVersion = sentVersion;
    }

    public Long getSentDatasetVersionId() { return sentDatasetVersionId; }
    public void setSentDatasetVersionId(Long id) { sentDatasetVersionId = id; }
    public Long getSentRevision() { return sentRevision; }
    public void setSentRevision(Long revision) { sentRevision = revision; }
    public boolean isTombstoneRequested() { return tombstoneRequested; }
    public void setTombstoneRequested(boolean requested) { tombstoneRequested = requested; }
    public boolean wasPublished() { return wasPublished; }
    public void setWasPublished(boolean published) { wasPublished = published; }

    public String getReservationToken() { return reservationToken; }
    public void setReservationToken(String token) { reservationToken = token; }
    public Timestamp getReservationLeaseUntil() { return reservationLeaseUntil; }
    public void setReservationLeaseUntil(Timestamp until) { reservationLeaseUntil = until; }

    public String getMetadataCid() {
        return metadataCid;
    }

    public void setMetadataCid(String metadataCid) {
        this.metadataCid = metadataCid;
    }

    public String getLevel1Cid() {
        return level1Cid;
    }

    public void setLevel1Cid(String level1Cid) {
        this.level1Cid = level1Cid;
    }

    public String getLevel2Cid() {
        return level2Cid;
    }

    public void setLevel2Cid(String level2Cid) {
        this.level2Cid = level2Cid;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public int getOtherIdAttempts() {
        return otherIdAttempts;
    }

    public void setOtherIdAttempts(int otherIdAttempts) {
        this.otherIdAttempts = otherIdAttempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    /** Touches {@code updated_at} (also when only a counter changed). */
    public void setUpdatedAtNow() {
        this.updatedAt = new Timestamp(System.currentTimeMillis());
    }

    /** Copies state and CIDs from a Minter response; fields absent in the response are kept. */
    public void applyResponse(DarkArkResponse response) {
        if (response.state() != null) {
            this.status = response.state();
        }
        if (response.target() != null) {
            this.target = response.target();
        }
        if (response.metadataCid() != null) {
            this.metadataCid = response.metadataCid();
        }
        if (response.level1Cid() != null) {
            this.level1Cid = response.level1Cid();
        }
        if (response.level2Cid() != null) {
            this.level2Cid = response.level2Cid();
        }
    }

    @Override
    public String toString() {
        return "Dark[id=" + id + ", ark=" + ark + ", status=" + status + "]";
    }
}
