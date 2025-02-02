package com.hubspot.singularity.scheduler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.mesos.v1.Protos.Address;
import org.apache.mesos.v1.Protos.AgentID;
import org.apache.mesos.v1.Protos.Attribute;
import org.apache.mesos.v1.Protos.ExecutorID;
import org.apache.mesos.v1.Protos.ExecutorInfo;
import org.apache.mesos.v1.Protos.FrameworkID;
import org.apache.mesos.v1.Protos.Offer;
import org.apache.mesos.v1.Protos.OfferID;
import org.apache.mesos.v1.Protos.Resource;
import org.apache.mesos.v1.Protos.TaskID;
import org.apache.mesos.v1.Protos.TaskInfo;
import org.apache.mesos.v1.Protos.TaskState;
import org.apache.mesos.v1.Protos.TaskStatus;
import org.apache.mesos.v1.Protos.URL;
import org.apache.mesos.v1.Protos.Value.Scalar;
import org.apache.mesos.v1.Protos.Value.Text;
import org.apache.mesos.v1.Protos.Value.Type;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.hubspot.baragon.models.BaragonRequestState;
import com.hubspot.deploy.HealthcheckOptionsBuilder;
import com.hubspot.mesos.Resources;
import com.hubspot.mesos.json.MesosTaskMonitorObject;
import com.hubspot.mesos.json.MesosTaskStatisticsObject;
import com.hubspot.mesos.protos.MesosTaskStatusObject;
import com.hubspot.singularity.DeployState;
import com.hubspot.singularity.LoadBalancerRequestType;
import com.hubspot.singularity.LoadBalancerRequestType.LoadBalancerRequestId;
import com.hubspot.singularity.RequestType;
import com.hubspot.singularity.SingularityCuratorTestBase;
import com.hubspot.singularity.SingularityDeploy;
import com.hubspot.singularity.SingularityDeployBuilder;
import com.hubspot.singularity.SingularityDeployMarker;
import com.hubspot.singularity.SingularityDeployProgress;
import com.hubspot.singularity.SingularityDeployResult;
import com.hubspot.singularity.SingularityKilledTaskIdRecord;
import com.hubspot.singularity.SingularityLoadBalancerUpdate;
import com.hubspot.singularity.SingularityLoadBalancerUpdate.LoadBalancerMethod;
import com.hubspot.singularity.SingularityMainModule;
import com.hubspot.singularity.SingularityPendingDeploy;
import com.hubspot.singularity.SingularityPendingRequest;
import com.hubspot.singularity.SingularityPendingRequest.PendingType;
import com.hubspot.singularity.SingularityPendingTask;
import com.hubspot.singularity.SingularityPendingTaskBuilder;
import com.hubspot.singularity.SingularityPendingTaskId;
import com.hubspot.singularity.SingularityRequest;
import com.hubspot.singularity.SingularityRequestBuilder;
import com.hubspot.singularity.SingularityRequestDeployState;
import com.hubspot.singularity.SingularityRequestHistory.RequestHistoryType;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskHistoryUpdate;
import com.hubspot.singularity.SingularityTaskHistoryUpdate.SimplifiedTaskState;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.SingularityTaskRequest;
import com.hubspot.singularity.SingularityTaskStatusHolder;
import com.hubspot.singularity.SingularityUser;
import com.hubspot.singularity.SlavePlacement;
import com.hubspot.singularity.api.SingularityDeployRequest;
import com.hubspot.singularity.api.SingularityScaleRequest;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.config.SingularityTaskMetadataConfiguration;
import com.hubspot.singularity.data.DeployManager;
import com.hubspot.singularity.data.InactiveSlaveManager;
import com.hubspot.singularity.data.PriorityManager;
import com.hubspot.singularity.data.RackManager;
import com.hubspot.singularity.data.RequestManager;
import com.hubspot.singularity.data.SlaveManager;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.data.zkmigrations.ZkDataMigrationRunner;
import com.hubspot.singularity.event.SingularityEventListener;
import com.hubspot.singularity.helpers.MesosProtosUtils;
import com.hubspot.singularity.helpers.MesosUtils;
import com.hubspot.singularity.mesos.SingularityMesosScheduler;
import com.hubspot.singularity.resources.DeployResource;
import com.hubspot.singularity.resources.PriorityResource;
import com.hubspot.singularity.resources.RackResource;
import com.hubspot.singularity.resources.RequestResource;
import com.hubspot.singularity.resources.SlaveResource;
import com.hubspot.singularity.resources.TaskResource;
import com.hubspot.singularity.smtp.SingularityMailer;
import com.ning.http.client.AsyncHttpClient;

public class SingularitySchedulerTestBase extends SingularityCuratorTestBase {

  @Inject
  protected SingularityLeaderCache leaderCache;
  @Inject
  protected SingularityMesosScheduler sms;
  @Inject
  protected RequestManager requestManager;
  @Inject
  protected DeployManager deployManager;
  @Inject
  protected TaskManager taskManager;
  @Inject
  protected PriorityManager priorityManager;
  @Inject
  protected SlaveManager slaveManager;
  @Inject
  protected RackManager rackManager;
  @Inject
  protected InactiveSlaveManager inactiveSlaveManager;
  @Inject
  protected SingularityScheduler scheduler;
  @Inject
  protected SingularityNewTaskChecker newTaskChecker;
  @Inject
  protected SingularityDeployChecker deployChecker;
  @Inject
  protected RackResource rackResource;
  @Inject
  protected SlaveResource slaveResource;
  @Inject
  protected TaskResource taskResource;
  @Inject
  protected RequestResource requestResource;
  @Inject
  protected DeployResource deployResource;
  @Inject
  protected PriorityResource priorityResource;
  @Inject
  protected SingularityCleaner cleaner;
  @Inject
  protected SingularityConfiguration configuration;
  @Inject
  protected SingularityTaskMetadataConfiguration taskMetadataConfiguration;
  @Inject
  protected SingularityCooldownChecker cooldownChecker;
  @Inject
  protected AsyncHttpClient httpClient;
  @Inject
  protected TestingLoadBalancerClient testingLbClient;
  @Inject
  protected SingularityTaskReconciliation taskReconciliation;
  @Inject
  protected SingularityMailer mailer;
  @Inject
  protected SingularityJobPoller scheduledJobPoller;
  @Inject
  protected ZkDataMigrationRunner migrationRunner;
  @Inject
  protected SingularityEventListener eventListener;
  @Inject
  protected SingularityExpiringUserActionPoller expiringUserActionPoller;
  @Inject
  protected SingularityPriorityKillPoller priorityKillPoller;
  @Inject
  protected SingularityHealthchecker healthchecker;
  @Inject
  protected SingularityAutoScaleSpreadAllPoller spreadAllPoller;

  @Inject
  protected SingularityLeaderCacheCoordinator cacheCoordinator;

  @Inject
  @Named(SingularityMainModule.SERVER_ID_PROPERTY)
  protected String serverId;

  protected String requestId = "test-request";
  protected SingularityRequest request;
  protected String schedule = "*/1 * * * * ?";

  protected String firstDeployId = "firstDeployId";
  protected SingularityDeploy firstDeploy;

  protected String secondDeployId = "secondDeployId";
  protected SingularityDeployMarker secondDeployMarker;
  protected SingularityDeploy secondDeploy;

  protected Optional<String> user = Optional.empty();

  protected SingularityUser singularityUser = SingularityUser.DEFAULT_USER;

  private final boolean runZkMigrations;

  public SingularitySchedulerTestBase(boolean useDBTests) {
    super(useDBTests, null);
    this.runZkMigrations = true;
  }

  public SingularitySchedulerTestBase(boolean useDBTests, boolean runZkMigrations) {
    super(useDBTests, null);
    this.runZkMigrations = runZkMigrations;
  }

  public SingularitySchedulerTestBase(boolean useDBTests, Function<SingularityConfiguration, Void> customConfigSetup) {
    super(useDBTests, customConfigSetup);
    this.runZkMigrations = true;
  }

  @AfterAll
  public void teardown() throws Exception {
    super.teardown();
    if (httpClient != null) {
      httpClient.close();
    }
  }

  @BeforeAll
  public void setup() throws Exception {
    super.setup();
    sms.setSubscribed();
    if (runZkMigrations) {
      migrationRunner.checkMigrations();
    }
    configuration.getMesosConfiguration().setFrameworkId("singularity");
  }

  protected Offer createOffer(double cpus, double memory, double disk) {
    return createOffer(cpus, memory, disk,"slave1", "host1", Optional.<String>empty());
  }

  protected Offer createOffer(double cpus, double memory, double disk, Optional<String> role) {
    return createOffer(cpus, memory, disk, "slave1", "host1", Optional.<String>empty(), Collections.<String, String> emptyMap(), new String[0], role);
  }

  protected Offer createOffer(double cpus, double memory, double disk, String slave, String host) {
    return createOffer(cpus, memory, disk, slave, host, Optional.<String>empty());
  }

  protected Offer createOffer(double cpus, double memory, double disk, String slave, String host, Optional<String> rack) {
    return createOffer(cpus, memory, disk, slave, host, rack, Collections.<String, String> emptyMap(), new String[0], Optional.<String>empty());
  }

  protected Offer createOffer(double cpus, double memory, double disk, String slave, String host, Optional<String> rack, Map<String, String> attributes) {
    return createOffer(cpus, memory, disk, slave, host, rack, attributes, new String[0], Optional.<String>empty());
  }

  protected Offer createOffer(double cpus, double memory, double disk, String slave, String host, Optional<String> rack, Map<String, String> attributes, String[] portRanges) {
    return createOffer(cpus, memory, disk, slave, host, rack, attributes, portRanges, Optional.<String>empty());
  }

  protected Offer createOffer(double cpus, double memory, double disk, String slave, String host, Optional<String> rack, Map<String, String> attributes, String[] portRanges, Optional<String> role) {
    AgentID slaveId = AgentID.newBuilder().setValue(slave).build();
    FrameworkID frameworkId = FrameworkID.newBuilder().setValue("framework1").build();

    Random r = new Random();

    List<Attribute> attributesList = new ArrayList<>();
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      attributesList.add(Attribute.newBuilder()
          .setType(Type.TEXT)
          .setName(entry.getKey())
          .setText(Text.newBuilder().setValue(entry.getValue()).build())
          .build());
    }

    Resource.Builder cpusResource = Resource.newBuilder().setType(Type.SCALAR).setName(MesosUtils.CPUS).setScalar(Scalar.newBuilder().setValue(cpus));
    Resource.Builder memoryResources = Resource.newBuilder().setType(Type.SCALAR).setName(MesosUtils.MEMORY).setScalar(Scalar.newBuilder().setValue(memory));
    Resource.Builder diskResources = Resource.newBuilder().setType(Type.SCALAR).setName(MesosUtils.DISK).setScalar(Scalar.newBuilder().setValue(disk));
    if(role.isPresent()) {
      cpusResource = cpusResource.setRole(role.get());
      memoryResources = memoryResources.setRole(role.get());
      diskResources = diskResources.setRole(role.get());
    }

    return Offer.newBuilder()
        .setId(OfferID.newBuilder().setValue("offer" + r.nextInt(1000)).build())
        .setFrameworkId(frameworkId)
        .setAgentId(slaveId)
        .setHostname(host)
        .setUrl(URL.newBuilder().setScheme("scheme").setAddress(Address.newBuilder().setPort(8080)))
        .addAttributes(Attribute.newBuilder().setType(Type.TEXT).setText(Text.newBuilder().setValue(rack.orElse(configuration.getMesosConfiguration().getDefaultRackId()))).setName(configuration.getMesosConfiguration().getRackIdAttributeKey()))
        .addResources(cpusResource)
        .addResources(memoryResources)
        .addResources(diskResources)
        .addResources(MesosUtilsTest.buildPortRanges(portRanges))
        .addAllAttributes(attributesList)
        .build();
  }

  protected SingularityTask launchTask(SingularityRequest request, SingularityDeploy deploy, int instanceNo, TaskState initialTaskState) {
    return launchTask(request, deploy, System.currentTimeMillis() - 1, System.currentTimeMillis(), instanceNo, initialTaskState, false, Optional.<String>empty(), Optional.empty());
  }

  protected SingularityTask launchTask(SingularityRequest request, SingularityDeploy deploy, int instanceNo, TaskState initialTaskState, boolean separateHost) {
    return launchTask(request, deploy, System.currentTimeMillis() - 1, System.currentTimeMillis(), instanceNo, initialTaskState, separateHost, Optional.<String>empty(), Optional.empty());
  }

  protected SingularityTask launchTask(SingularityRequest request, SingularityDeploy deploy, long launchTime, long updateTime, int instanceNo, TaskState initialTaskState, boolean separateHost) {
    return launchTask(request, deploy, launchTime, updateTime, instanceNo, initialTaskState, separateHost, Optional.<String>empty(), Optional.empty());
  }

  protected SingularityTask launchTask(SingularityRequest request, SingularityDeploy deploy, long taskLaunch, int instanceNo, TaskState initialTaskState) {
    return launchTask(request, deploy, taskLaunch, System.currentTimeMillis(), instanceNo, initialTaskState, false, Optional.<String>empty(), Optional.empty());
  }

  protected SingularityTask launchTask(SingularityRequest request, SingularityDeploy deploy, long launchTime, long updateTime, int instanceNo, TaskState initialTaskState) {
    return launchTask(request, deploy, launchTime, updateTime, instanceNo, initialTaskState, false, Optional.<String>empty(), Optional.empty());
  }

  protected SingularityPendingTask buildPendingTask(SingularityRequest request, SingularityDeploy deploy, long launchTime, int instanceNo, Optional<String> runId) {
    SingularityPendingTaskId pendingTaskId = new SingularityPendingTaskId(request.getId(), deploy.getId(), launchTime, instanceNo, PendingType.IMMEDIATE, launchTime);
    SingularityPendingTask pendingTask = new SingularityPendingTaskBuilder()
        .setPendingTaskId(pendingTaskId)
        .setRunId(runId)
        .build();

    return pendingTask;
  }

  protected SingularityTask prepTask(SingularityRequest request, SingularityDeploy deploy, long launchTime, int instanceNo) {
    return prepTask(request, deploy, launchTime, instanceNo, false, Optional.empty(), Optional.empty());
  }


  protected SingularityTask prepTask(SingularityRequest request, SingularityDeploy deploy, long launchTime, int instanceNo, boolean separateHosts, Optional<String> runId, Optional<String> slaveAndRack) {
    SingularityPendingTask pendingTask = buildPendingTask(request, deploy, launchTime, instanceNo, runId);
    SingularityTaskRequest taskRequest = new SingularityTaskRequest(request, deploy, pendingTask);

    Offer offer;
    if (separateHosts || slaveAndRack.isPresent()) {
      offer = createOffer(125, 1024, 2048, slaveAndRack.orElse(String.format("slave%s", instanceNo)), slaveAndRack.orElse(String.format("host%s", instanceNo)), slaveAndRack);
    } else {
      offer = createOffer(125, 1024, 2048);
    }

    SingularityTaskId taskId = new SingularityTaskId(request.getId(), deploy.getId(), launchTime, instanceNo, offer.getHostname(), slaveAndRack.orElse("rack1"));
    TaskID taskIdProto = TaskID.newBuilder().setValue(taskId.toString()).build();

    TaskInfo taskInfo = TaskInfo.newBuilder()
        .setAgentId(offer.getAgentId())
        .setExecutor(ExecutorInfo.newBuilder().setExecutorId(ExecutorID.newBuilder().setValue("executorID")))
        .setTaskId(taskIdProto)
        .setName("name")
        .build();

    SingularityTask task = new SingularityTask(taskRequest, taskId, Collections.singletonList(mesosProtosUtils.offerFromProtos(offer)), mesosProtosUtils.taskFromProtos(taskInfo), Optional.of("rack1"));

    taskManager.savePendingTask(pendingTask);

    return task;
  }

  protected SingularityTask prepTask() {
    return prepTask(request, firstDeploy, System.currentTimeMillis(), 1, false, Optional.<String>empty(), Optional.empty());
  }

  protected SingularityTask launchTask(SingularityRequest request, SingularityDeploy deploy, long launchTime, long updateTime, int instanceNo, TaskState initialTaskState, boolean separateHost, Optional<String> runId, Optional<String> slave) {
    SingularityTask task = prepTask(request, deploy, launchTime, instanceNo, separateHost, runId, slave);

    taskManager.createTaskAndDeletePendingTask(task);

    statusUpdate(task, initialTaskState, Optional.of(updateTime));

    return task;
  }

  protected void statusUpdate(SingularityTask task, TaskState state, Optional<Long> timestamp) {
    TaskStatus.Builder bldr = TaskStatus.newBuilder()
        .setTaskId(MesosProtosUtils.toTaskId(task.getMesosTask().getTaskId()))
        .setAgentId(MesosProtosUtils.toAgentId(task.getAgentId()))
        .setState(state);

    if (timestamp.isPresent()) {
      bldr.setTimestamp(timestamp.get() / 1000);
    }

    sms.statusUpdate(bldr.build()).join();
  }

  protected void statusUpdate(SingularityTask task, TaskState state) {
    statusUpdate(task, state, Optional.<Long>empty());
  }

  protected void runLaunchedTasks() {
    for (SingularityTaskId taskId : taskManager.getActiveTaskIds()) {
      Collection<SingularityTaskHistoryUpdate> updates = taskManager.getTaskHistoryUpdates(taskId);

      SimplifiedTaskState currentState = SingularityTaskHistoryUpdate.getCurrentState(updates);

      switch (currentState) {
        case UNKNOWN:
        case WAITING:
          statusUpdate(taskManager.getTask(taskId).get(), TaskState.TASK_RUNNING);
          break;
        case DONE:
        case RUNNING:
          break;
      }
    }
  }

  protected void killKilledTasks() {
    for (SingularityKilledTaskIdRecord killed : taskManager.getKilledTaskIdRecords()) {
      statusUpdate(taskManager.getTask(killed.getTaskId()).get(), TaskState.TASK_KILLED);
    }
    scheduler.drainPendingQueue();
  }

  protected void finishNewTaskChecksAndCleanup() {
    cleaner.drainCleanupQueue();
    killKilledTasks();
  }

  protected void finishHealthchecks() {
    for (ScheduledFuture<?> future : healthchecker.getHealthCheckFutures()) {
      try {
        future.get();
      } catch (CancellationException ce) {
        // ignore, expected due to highly concurrent.
      } catch (InterruptedException e) {
        return;
      } catch (ExecutionException e) {
        throw new RuntimeException(e);
      }
    }
  }

  protected void initLoadBalancedRequest() {
    protectedInitRequest(true, false);
  }

  protected void initScheduledRequest() {
    protectedInitRequest(false, true);
  }

  protected void saveRequest(SingularityRequest request) {
    requestManager.activate(request, RequestHistoryType.CREATED, System.currentTimeMillis(), Optional.<String>empty(), Optional.<String>empty());
  }

  protected void initOnDemandRequest() {
    initRequestWithType(RequestType.ON_DEMAND, false);
  }

  protected SingularityRequest createRequest(String requestId) {
    SingularityRequestBuilder bldr = new SingularityRequestBuilder(requestId, RequestType.SERVICE);

    bldr.setInstances(Optional.of(5));
    bldr.setSlavePlacement(Optional.of(SlavePlacement.SEPARATE));

    SingularityRequest request = bldr.build();

    saveRequest(bldr.build());

    return request;
  }

  protected SingularityDeploy deployRequest(SingularityRequest request, double cpus, double memoryMb) {
    Resources r = new Resources(cpus, memoryMb, 0);

    SingularityDeploy deploy = new SingularityDeployBuilder(request.getId(), "deploy1")
        .setCommand(Optional.of("sleep 1"))
        .setResources(Optional.of(r))
        .build();

    deployResource.deploy(new SingularityDeployRequest(deploy, Optional.empty(), Optional.empty()), singularityUser);

    return deploy;
  }

  protected void createAndDeployRequest(String requestId, double cpus, double memory) {
    deployRequest(createRequest(requestId), cpus, memory);
  }

  protected void initRequestWithType(RequestType requestType, boolean isLoadBalanced) {
    SingularityRequestBuilder bldr = new SingularityRequestBuilder(requestId, requestType);

    bldr.setLoadBalanced(Optional.of(isLoadBalanced));

    if (requestType == RequestType.SCHEDULED) {
      bldr.setQuartzSchedule(Optional.of(schedule));
    }

    request = bldr.build();

    saveRequest(request);
  }

  protected SingularityRequest startAndDeploySecondRequest() {
    SingularityRequest request = new SingularityRequestBuilder(requestId + "2", RequestType.SERVICE).build();
    saveRequest(request);

    SingularityDeploy deploy = new SingularityDeployBuilder(request.getId(), "d1").setCommand(Optional.of("sleep 1")).build();

    deployResource.deploy(new SingularityDeployRequest(deploy, Optional.empty(), Optional.empty()), singularityUser);

    return request;
  }

  protected void protectedInitRequest(boolean isLoadBalanced, boolean isScheduled) {
    RequestType requestType = RequestType.WORKER;

    if (isScheduled) {
      requestType = RequestType.SCHEDULED;
    }

    initRequestWithType(requestType, isLoadBalanced);
  }

  protected void initRequest() {
    protectedInitRequest(false, false);
  }

  protected void initWithTasks(int num) {
    initRequest();

    requestResource.scale(requestId, new SingularityScaleRequest(Optional.of(num), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), singularityUser);

    initFirstDeploy();

    startTasks(num);
  }

  protected SingularityDeploy startFirstDeploy() {
    firstDeploy = initDeploy(new SingularityDeployBuilder(request.getId(), firstDeployId).setCommand(Optional.of("sleep 100")), System.currentTimeMillis());
    return firstDeploy;
  }

  protected void initFirstDeploy() {
    firstDeploy = initAndFinishDeploy(request, firstDeployId);
  }

  protected void initFirstDeployWithResources(double cpus, double memoryMb) {
    firstDeploy = initAndFinishDeployWithResources(request, firstDeployId, cpus, memoryMb);
  }

  protected void initHCDeploy() {
    firstDeploy = initAndFinishDeploy(request, new SingularityDeployBuilder(request.getId(), firstDeployId).setResources(Optional.of(new Resources(0.1, 1, 1))).setCommand(Optional.of("sleep 100")).setHealthcheck(Optional.of(new HealthcheckOptionsBuilder("http://uri").build())), Optional.empty());
  }

  protected void initLoadBalancedDeploy() {
    SingularityDeployBuilder builder = new SingularityDeployBuilder(requestId, firstDeployId)
        .setCommand(Optional.of("sleep 100"))
        .setServiceBasePath(Optional.of("/basepath"))
        .setLoadBalancerGroups(Optional.of(Collections.singleton("test")));
    firstDeploy = initAndFinishDeploy(request, builder, Optional.empty());
  }

  protected SingularityDeploy initAndFinishDeploy(SingularityRequest request, String deployId) {
    return initAndFinishDeploy(request, new SingularityDeployBuilder(request.getId(), deployId).setCommand(Optional.of("sleep 100")), Optional.empty());
  }

  protected SingularityDeploy initAndFinishDeployWithResources(SingularityRequest request, String deployId, double cpus, double memoryMb) {
    Resources r = new Resources(cpus, memoryMb, 0);

    return initAndFinishDeploy(request, new SingularityDeployBuilder(request.getId(), deployId).setCommand(Optional.of("sleep 100")), Optional.of(r));
  }

  protected SingularityDeploy initAndFinishDeploy(SingularityRequest request, SingularityDeployBuilder builder, Optional<Resources> maybeResources) {
    SingularityDeploy deploy = builder.setResources(maybeResources).build();

    SingularityDeployMarker marker = new SingularityDeployMarker(deploy.getRequestId(), deploy.getId(), System.currentTimeMillis(), Optional.<String>empty(), Optional.<String>empty());

    deployManager.saveDeploy(request, marker, deploy);

    finishDeploy(marker, deploy);

    return deploy;
  }

  protected SingularityDeploy initDeploy(SingularityDeployBuilder builder, long timestamp) {
    SingularityDeployMarker marker = new SingularityDeployMarker(requestId, builder.getId(), timestamp, Optional.<String>empty(), Optional.<String>empty());
    builder.setCommand(Optional.of("sleep 100"));

    SingularityDeploy deploy = builder.build();

    deployManager.saveDeploy(request, marker, deploy);

    startDeploy(marker, timestamp);

    return deploy;
  }

  protected SingularityDeployMarker initSecondDeploy() {
    secondDeployMarker = new SingularityDeployMarker(requestId, secondDeployId, System.currentTimeMillis(), Optional.<String>empty(), Optional.<String>empty());
    secondDeploy = new SingularityDeployBuilder(requestId, secondDeployId).setCommand(Optional.of("sleep 100")).build();

    deployManager.saveDeploy(request, secondDeployMarker, secondDeploy);

    startDeploy(secondDeployMarker, System.currentTimeMillis());

    return secondDeployMarker;
  }

  protected void startDeploy(SingularityDeployMarker deployMarker, long timestamp) {
    SingularityDeployProgress startingDeployProgress = new SingularityDeployProgress(1, 0, 1, 10, false, true, Collections.<SingularityTaskId>emptySet(), timestamp);
    deployManager.savePendingDeploy(new SingularityPendingDeploy(deployMarker, Optional.<SingularityLoadBalancerUpdate>empty(), DeployState.WAITING, Optional.of(startingDeployProgress), Optional.<SingularityRequest>empty()));
  }

  protected void finishDeploy(SingularityDeployMarker marker, SingularityDeploy deploy) {
    deployManager.deletePendingDeploy(marker.getRequestId());

    deployManager.saveDeployResult(marker, Optional.of(deploy), new SingularityDeployResult(DeployState.SUCCEEDED));

    deployManager.saveNewRequestDeployState(new SingularityRequestDeployState(marker.getRequestId(), Optional.of(marker), Optional.<SingularityDeployMarker>empty()));
  }

  protected SingularityTask startTask(SingularityDeploy deploy) {
    return startTask(deploy, 1);
  }

  protected SingularityTask startTask(SingularityDeploy deploy, int instanceNo) {
    return launchTask(request, deploy, instanceNo, TaskState.TASK_RUNNING);
  }

  protected void startTasks(int num) {
    for (int i = 1; i < num + 1; i++) {
      startTask(firstDeploy, i);
    }
  }

  protected SingularityTask startSeparatePlacementTask(SingularityDeploy deploy, int instanceNo) {
    return launchTask(request, deploy, instanceNo, TaskState.TASK_RUNNING, true);
  }

  protected List<Offer> resourceOffers() {
    Offer offer1 = createOffer(20, 20000, 50000, "slave1", "host1");
    Offer offer2 = createOffer(20, 20000, 50000, "slave2", "host2");

    List<Offer> offers = Arrays.asList(offer1, offer2);

    sms.resourceOffers(offers);

    return offers;
  }

  protected void resourceOffersByNumTasks(int numTasks) {
    List<Offer> offers = new ArrayList<>();
    for (int i = 1; i <= numTasks; i++) {
      offers.add(createOffer(1, 128, 1024, String.format("slave%s", i), String.format("host%s", i)));
    }
    sms.resourceOffers(offers);
  }

  protected void resourceOffers(int numSlaves) {
    List<Offer> offers = new ArrayList<>();
    for (int i = 1; i <= numSlaves; i++) {
      offers.add(createOffer(20, 20000, 50000, String.format("slave%s", i), String.format("host%s", i)));
    }
    sms.resourceOffers(offers);
  }

  protected void deploy(String deployId) {
    deploy(deployId, Optional.<Boolean>empty(), Optional.<Integer>empty(), Optional.<Boolean>empty(), false);
  }

  protected void deploy(String deployId, Optional<Boolean> unpauseOnDeploy) {
    deploy(deployId, unpauseOnDeploy, Optional.<Integer>empty(), Optional.<Boolean>empty(), false);
  }

  protected void deploy(String deployId, Optional<Boolean> unpauseOnDeploy, Optional<Integer> deployRate, Optional<Boolean> autoAdvance, boolean loadBalanced) {
    SingularityDeployBuilder builder = new SingularityDeployBuilder(requestId, deployId);
    builder
        .setCommand(Optional.of("sleep 1"))
        .setDeployInstanceCountPerStep(deployRate)
        .setAutoAdvanceDeploySteps(autoAdvance)
        .setDeployStepWaitTimeMs(Optional.of(0));
    if (loadBalanced) {
      Set<String> groups = new HashSet<>(Arrays.asList("group"));
      builder
          .setServiceBasePath(Optional.of("/basepath"))
          .setLoadBalancerGroups(Optional.of(groups));
    }
    deployResource.deploy(new SingularityDeployRequest(builder.build(), unpauseOnDeploy, Optional.empty()), singularityUser);
  }

  protected SingularityPendingTask createAndSchedulePendingTask(String deployId) {
    Random random = new Random();

    SingularityPendingTaskId pendingTaskId = new SingularityPendingTaskId(requestId, deployId,
        System.currentTimeMillis() + TimeUnit.DAYS.toMillis(random.nextInt(3)), random.nextInt(10), PendingType.NEW_DEPLOY, System.currentTimeMillis());

    SingularityPendingTask pendingTask = new SingularityPendingTaskBuilder().setPendingTaskId(pendingTaskId).build();

    taskManager.savePendingTask(pendingTask);

    return pendingTask;
  }

  protected void saveAndSchedule(SingularityRequestBuilder bldr) {
    SingularityRequest build = bldr.build();
    requestManager.activate(build, RequestHistoryType.UPDATED, System.currentTimeMillis(), Optional.<String>empty(), Optional.<String>empty());
    requestManager.addToPendingQueue(new SingularityPendingRequest(build.getId(), firstDeployId, System.currentTimeMillis(), Optional.<String>empty(), PendingType.UPDATED_REQUEST, Optional.<Boolean>empty(), Optional.<String>empty()));
    scheduler.drainPendingQueue();
  }

  protected void saveLoadBalancerState(BaragonRequestState brs, SingularityTaskId taskId, LoadBalancerRequestType lbrt) {
    final LoadBalancerRequestId lbri = new LoadBalancerRequestId(taskId.getId(), lbrt, Optional.<Integer>empty());
    SingularityLoadBalancerUpdate update = new SingularityLoadBalancerUpdate(brs, lbri, Optional.<String>empty(), System.currentTimeMillis(), LoadBalancerMethod.CHECK_STATE, null);

    taskManager.saveLoadBalancerState(taskId, lbrt, update);
  }

  protected void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected void saveLastActiveTaskStatus(SingularityTask task, Optional<MesosTaskStatusObject> taskStatus, long millisAdjustment) {
    taskManager.saveLastActiveTaskStatus(new SingularityTaskStatusHolder(task.getTaskId(), taskStatus, System.currentTimeMillis() + millisAdjustment, serverId, Optional.of("slaveId")));
  }

  protected MesosTaskStatusObject buildTaskStatus(SingularityTask task) {
    return mesosProtosUtils.taskStatusFromProtos(TaskStatus.newBuilder().setTaskId(TaskID.newBuilder().setValue(task.getTaskId().getId())).setState(TaskState.TASK_RUNNING).build());
  }

  protected SingularityRequest buildRequest(String requestId) {
    SingularityRequest request = new SingularityRequestBuilder(requestId, RequestType.WORKER).build();

    saveRequest(request);

    return request;
  }

  protected SingularityTaskRequest buildTaskRequest(SingularityRequest request, SingularityDeploy deploy, long launchTime) {
    return new SingularityTaskRequest(request, deploy, buildPendingTask(request, deploy, launchTime, 100, Optional.<String>empty()));
  }

  protected MesosTaskStatisticsObject getStatistics(double cpuSecs, double timestamp, long memBytes) {
    return new MesosTaskStatisticsObject(1, 0L, 0L, 0, 0, cpuSecs, 0L, 0L, 0L, 0L, 0L, memBytes, 0L, 0L, timestamp);
  }

  protected MesosTaskMonitorObject getTaskMonitor(String id, double cpuSecs, double timestampSeconds, int memBytes) {
    return new MesosTaskMonitorObject(null, null, "singularity", id, getStatistics(cpuSecs, timestampSeconds, memBytes));
  }

}
