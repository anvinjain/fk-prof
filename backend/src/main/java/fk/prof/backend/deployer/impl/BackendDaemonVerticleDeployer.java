package fk.prof.backend.deployer.impl;

import com.google.common.base.Preconditions;
import fk.prof.backend.ConfigManager;
import fk.prof.backend.deployer.VerticleDeployer;
import fk.prof.backend.model.aggregation.AggregationWindowLookupStore;
import fk.prof.backend.model.assignment.ProcessGroupAssociationStore;
import fk.prof.backend.model.election.LeaderReadContext;
import fk.prof.backend.model.slot.WorkSlotPool;
import fk.prof.backend.worker.BackendDaemon;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;

public class BackendDaemonVerticleDeployer extends VerticleDeployer {

  private final LeaderReadContext leaderReadContext;
  private final ProcessGroupAssociationStore processGroupAssociationStore;
  private final AggregationWindowLookupStore aggregationWindowLookupStore;
  private final WorkSlotPool workSlotPool;

  public BackendDaemonVerticleDeployer(Vertx vertx,
                                       ConfigManager configManager,
                                       LeaderReadContext leaderReadContext,
                                       ProcessGroupAssociationStore processGroupAssociationStore,
                                       AggregationWindowLookupStore aggregationWindowLookupStore,
                                       WorkSlotPool workSlotPool) {
    super(vertx, configManager);
    this.leaderReadContext = Preconditions.checkNotNull(leaderReadContext);
    this.processGroupAssociationStore = Preconditions.checkNotNull(processGroupAssociationStore);
    this.aggregationWindowLookupStore = Preconditions.checkNotNull(aggregationWindowLookupStore);
    this.workSlotPool = Preconditions.checkNotNull(workSlotPool);
  }

  @Override
  protected DeploymentOptions getDeploymentOptions() {
    return new DeploymentOptions(getConfigManager().getBackendDaemonDeploymentConfig());
  }

  @Override
  protected Verticle buildVerticle() {
    return new BackendDaemon(getConfigManager(), leaderReadContext, processGroupAssociationStore, aggregationWindowLookupStore, workSlotPool);
  }
}
