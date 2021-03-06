package com.mesosphere.sdk.scheduler;

import com.mesosphere.sdk.dcos.Capabilities;
import com.mesosphere.sdk.dcos.DcosVersion;
import com.mesosphere.sdk.framework.Driver;
import com.mesosphere.sdk.framework.TaskKiller;
import com.mesosphere.sdk.offer.Constants;
import com.mesosphere.sdk.scheduler.plan.*;
import com.mesosphere.sdk.specification.*;
import com.mesosphere.sdk.state.ConfigStore;
import com.mesosphere.sdk.state.FrameworkStore;
import com.mesosphere.sdk.state.StateStore;
import com.mesosphere.sdk.state.StateStoreUtils;
import com.mesosphere.sdk.storage.MemPersister;
import com.mesosphere.sdk.storage.Persister;
import com.mesosphere.sdk.storage.PersisterException;
import com.mesosphere.sdk.testutils.OfferTestUtils;
import com.mesosphere.sdk.testutils.ResourceTestUtils;
import com.mesosphere.sdk.testutils.SchedulerConfigTestUtils;
import com.mesosphere.sdk.testutils.TestConstants;
import com.mesosphere.sdk.testutils.TestPodFactory;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.SchedulerDriver;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.mesosphere.sdk.dcos.DcosConstants.DEFAULT_GPU_POLICY;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyCollectionOf;
import static org.mockito.Mockito.*;

/**
 * This class tests the {@link DefaultScheduler} class.
 */
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.AvoidUsingHardCodedIP"})
public class DefaultSchedulerTest {
    @Mock
    private SchedulerDriver mockSchedulerDriver;
    @Mock
    private SchedulerConfig mockSchedulerConfig;
    @Captor
    private ArgumentCaptor<Collection<Protos.Offer.Operation>> operationsCaptor;
    @Captor
    private ArgumentCaptor<Collection<Protos.Offer.Operation>> operationsCaptor2;

    private static final String TASK_IP = "9.9.9.9";

    private static final int TASK_A_COUNT = 1;
    private static final String TASK_A_POD_NAME = "POD-A";
    private static final String TASK_A_NAME = "A";
    private static final double TASK_A_CPU = 1.0;
    private static final double UPDATED_TASK_A_CPU = TASK_A_CPU + 1.0;
    private static final double TASK_A_MEM = 1000.0;
    private static final double TASK_A_DISK = 1500.0;
    private static final String TASK_A_CMD = "echo " + TASK_A_NAME;

    private static final int TASK_B_COUNT = 2;
    private static final String TASK_B_POD_NAME = "POD-B";
    private static final String TASK_B_NAME = "B";
    private static final double TASK_B_CPU = 2.0;
    private static final double TASK_B_MEM = 2000.0;
    private static final double UPDATED_TASK_B_MEM = 2000.0 * 2;
    private static final double TASK_B_DISK = 2500.0;
    private static final String TASK_B_CMD = "echo " + TASK_B_NAME;

    private static final PodSpec podA = TestPodFactory.getPodSpec(
            TASK_A_POD_NAME,
            TestConstants.RESOURCE_SET_ID + "-A",
            TASK_A_NAME,
            TASK_A_CMD,
            TestConstants.SERVICE_USER,
            TASK_A_COUNT,
            TASK_A_CPU,
            TASK_A_MEM,
            TASK_A_DISK);

    private static final PodSpec podB = TestPodFactory.getPodSpec(
            TASK_B_POD_NAME,
            TestConstants.RESOURCE_SET_ID + "-B",
            TASK_B_NAME,
            TASK_B_CMD,
            TestConstants.SERVICE_USER,
            TASK_B_COUNT,
            TASK_B_CPU,
            TASK_B_MEM,
            TASK_B_DISK);

    private static final PodSpec updatedPodA = TestPodFactory.getPodSpec(
            TASK_A_POD_NAME,
            TestConstants.RESOURCE_SET_ID + "-A",
            TASK_A_NAME,
            TASK_A_CMD,
            TestConstants.SERVICE_USER,
            TASK_A_COUNT,
            UPDATED_TASK_A_CPU,
            TASK_A_MEM,
            TASK_A_DISK);

    private static final PodSpec updatedPodB = TestPodFactory.getPodSpec(
            TASK_B_POD_NAME,
            TestConstants.RESOURCE_SET_ID + "-B",
            TASK_B_NAME,
            TASK_B_CMD,
            TestConstants.SERVICE_USER,
            TASK_B_COUNT,
            TASK_B_CPU,
            UPDATED_TASK_B_MEM,
            TASK_B_DISK);

    private static final PodSpec invalidPodB = TestPodFactory.getPodSpec(
            TASK_B_POD_NAME,
            TestConstants.RESOURCE_SET_ID + "-B",
            TASK_B_NAME,
            TASK_B_CMD,
            TestConstants.SERVICE_USER,
            TASK_B_COUNT - 1,
            TASK_B_CPU,
            TASK_B_MEM,
            TASK_B_DISK);

    private static final PodSpec scaledPodA = TestPodFactory.getPodSpec(
            TASK_A_POD_NAME,
            TestConstants.RESOURCE_SET_ID + "-A",
            TASK_A_NAME,
            TASK_A_CMD,
            TestConstants.SERVICE_USER,
            TASK_A_COUNT + 1,
            TASK_A_CPU,
            TASK_A_MEM,
            TASK_A_DISK);

    private static ServiceSpec getServiceSpec(PodSpec... pods) {
        return DefaultServiceSpec.newBuilder()
                .name(TestConstants.SERVICE_NAME)
                .role(TestConstants.ROLE)
                .principal(TestConstants.PRINCIPAL)
                .zookeeperConnection("badhost-shouldbeignored:2181")
                .pods(Arrays.asList(pods))
                .user(TestConstants.SERVICE_USER)
                .build();
    }

    private Capabilities getCapabilities() throws Exception {
        return new Capabilities(new DcosVersion("1.10-dev")) {
            @Override
            public boolean supportsGpuResource() {
                return DEFAULT_GPU_POLICY;
            }

            @Override
            public boolean supportsDomains() {
                return true;
            }
        };
    }

    private Persister persister;
    private StateStore stateStore;
    private ConfigStore<ServiceSpec> configStore;
    private DefaultScheduler defaultScheduler;

    @BeforeClass
    public static void beforeAll() {
        // Disable background TaskKiller thread, to avoid erroneous kill invocations
        try {
            TaskKiller.reset(false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterClass
    public static void afterAll() {
        // Re-enable TaskKiller thread
        try {
            TaskKiller.reset(false);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Before
    public void beforeEach() throws Exception {
        MockitoAnnotations.initMocks(this);
        Driver.setDriver(mockSchedulerDriver);

        when(mockSchedulerConfig.isStateCacheEnabled()).thenReturn(true);
        ServiceSpec serviceSpec = getServiceSpec(podA, podB);
        Capabilities.overrideCapabilities(getCapabilities());
        persister = new MemPersister();
        // Emulate behavior of upstream FrameworkScheduler, which handled registering with Mesos:
        new FrameworkStore(persister).storeFrameworkId(TestConstants.FRAMEWORK_ID);
        stateStore = new StateStore(persister);
        configStore = new ConfigStore<>(
                DefaultServiceSpec.getConfigurationFactory(serviceSpec), persister);
        defaultScheduler = getScheduler(serviceSpec);
    }

    @Test
    public void testEmptyOffers() {
        // Reconcile already triggered via registration during setup:
        defaultScheduler.offers(Collections.emptyList());
        verify(mockSchedulerDriver, times(0)).acceptOffers(any(), anyCollectionOf(Protos.Offer.Operation.class), any());
        verify(mockSchedulerDriver, times(0)).declineOffer(any(), any());
    }

    @Test
    public void testLaunchA() throws Exception {
        installStep(0, 0, getSufficientOfferForTaskA(), Status.PENDING);
        Assert.assertEquals(Arrays.asList(Status.COMPLETE, Status.PENDING, Status.PENDING),
                getStepStatuses(getDeploymentPlan()));
    }

    @Test
    public void testLaunchB() throws Exception {
        // Launch A-0
        testLaunchA();
        installStep(1, 0, getSufficientOfferForTaskB(), Status.PENDING);
        Assert.assertEquals(Arrays.asList(Status.COMPLETE, Status.COMPLETE, Status.PENDING),
                getStepStatuses(getDeploymentPlan()));
    }

    @Test
    public void testFailLaunchA() throws Exception {
        // Get first Step associated with Task A-0
        Plan plan = getDeploymentPlan();
        Step stepTaskA0 = plan.getChildren().get(0).getChildren().get(0);
        Assert.assertTrue(stepTaskA0.isPending());

        // Offer insufficient Resource and wait for step state transition
        UUID offerId = UUID.randomUUID();
        defaultScheduler.offers(Collections.singletonList(getInsufficientOfferForTaskA(offerId)));
        Assert.assertEquals(Arrays.asList(Status.PREPARED, Status.PENDING, Status.PENDING),
                getStepStatuses(plan));
    }

    @Test
    public void updatePerTaskASpecification() throws InterruptedException, IOException, Exception {
        // Launch A and B in original configuration
        testLaunchB();
        Capabilities.overrideCapabilities(getCapabilities());
        defaultScheduler = getScheduler(getServiceSpec(updatedPodA, podB));

        Assert.assertEquals(Arrays.asList(Status.PENDING, Status.COMPLETE, Status.PENDING),
                getStepStatuses(getDeploymentPlan()));
    }

    @Test
    public void updatePerTaskBSpecification() throws InterruptedException, IOException, Exception {
        // Launch A and B in original configuration
        testLaunchB();
        Capabilities.overrideCapabilities(getCapabilities());
        defaultScheduler = getScheduler(getServiceSpec(podA, updatedPodB));

        Assert.assertEquals(Arrays.asList(Status.COMPLETE, Status.PENDING, Status.PENDING),
                getStepStatuses(getDeploymentPlan()));
    }

    @Test
    public void updateTaskTypeASpecification() throws InterruptedException, IOException, Exception {
        // Launch A and B in original configuration
        testLaunchB();

        Capabilities.overrideCapabilities(getCapabilities());
        defaultScheduler = getScheduler(getServiceSpec(scaledPodA, podB));

        Assert.assertEquals(
                Arrays.asList(Status.COMPLETE, Status.PENDING, Status.COMPLETE, Status.PENDING),
                getStepStatuses(getDeploymentPlan()));
    }

    @Test
    public void testLaunchAndRecovery() throws Exception {
        // Get first Step associated with Task A-0
        Plan plan = getDeploymentPlan();
        Step stepTaskA0 = plan.getChildren().get(0).getChildren().get(0);
        Assert.assertTrue(stepTaskA0.isPending());

        // Offer sufficient Resources and wait for its acceptance
        Protos.Offer offer1 = getSufficientOfferForTaskA();
        defaultScheduler.offers(Collections.singletonList(offer1));
        verify(mockSchedulerDriver, times(1)).acceptOffers(
                collectionThat(contains(offer1.getId())),
                operationsCaptor.capture(),
                any());

        Collection<Protos.Offer.Operation> operations = operationsCaptor.getValue();
        Protos.TaskID launchedTaskId = getTaskId(operations);

        // Sent TASK_RUNNING status
        statusUpdate(launchedTaskId, Protos.TaskState.TASK_RUNNING);

        // Wait for the Step to become Complete
        Assert.assertTrue(stepTaskA0.isComplete());
        Assert.assertEquals(Arrays.asList(Status.COMPLETE, Status.PENDING, Status.PENDING),
                getStepStatuses(plan));

        // Sent TASK_KILLED status
        statusUpdate(launchedTaskId, Protos.TaskState.TASK_KILLED);

        reset(mockSchedulerDriver);

        // Make offers sufficient to recover Task A-0 and launch Task B-0,
        // and also have some unused reserved resources for cleaning, and verify that only one of those three happens.

        Protos.Resource cpus = ResourceTestUtils.getReservedCpus(1.0, UUID.randomUUID().toString());
        Protos.Resource mem = ResourceTestUtils.getReservedMem(1.0, UUID.randomUUID().toString());

        Protos.Offer offerA = Protos.Offer.newBuilder(getSufficientOfferForTaskA())
                .addAllResources(operations.stream()
                        .filter(Protos.Offer.Operation::hasReserve)
                        .flatMap(operation -> operation.getReserve().getResourcesList().stream())
                        .collect(Collectors.toList()))
                .addResources(cpus)
                .addResources(mem)
                .build();
        Protos.Offer offerB = Protos.Offer.newBuilder(getSufficientOfferForTaskB())
                .addAllResources(operations.stream()
                        .filter(Protos.Offer.Operation::hasReserve)
                        .flatMap(operation -> operation.getReserve().getResourcesList().stream())
                        .collect(Collectors.toList()))
                .addResources(cpus)
                .addResources(mem)
                .build();
        Protos.Offer offerC = Protos.Offer.newBuilder(getSufficientOfferForTaskB())
                .addAllResources(operations.stream()
                        .filter(Protos.Offer.Operation::hasReserve)
                        .flatMap(operation -> operation.getReserve().getResourcesList().stream())
                        .collect(Collectors.toList()))
                .addResources(cpus)
                .addResources(mem)
                .build();

        Collection<Protos.Offer> offers = Arrays.asList(offerA, offerB, offerC);
        defaultScheduler.offers(offers);

        // Verify that acceptOffer is called thrice, once each for recovery, launch, and cleanup.
        // Use a separate captor as the other one was already used against an acceptOffers call in this test case.
        verify(mockSchedulerDriver, times(3)).acceptOffers(
                any(),
                operationsCaptor2.capture(),
                any());
        final List<Collection<Protos.Offer.Operation>> allOperations = operationsCaptor2.getAllValues();
        Assert.assertEquals(3, allOperations.size());
        boolean recovery = false;
        boolean launch = false;
        boolean unreserve = false;

        for (Collection<Protos.Offer.Operation> operationSet : allOperations) {
            switch (operationSet.size()) {
                case 1:
                    // One LAUNCH operation
                    if (operationSet.iterator().next().getType()
                            == Protos.Offer.Operation.Type.LAUNCH_GROUP) {
                        recovery = true;
                    }
                    break;
                case 2:
                    // Two UNRESERVE operations
                    if (operationSet.stream().allMatch(object -> object.getType()
                            == Protos.Offer.Operation.Type.UNRESERVE)) {
                        recovery = true;
                    }
                    unreserve = true;
                    break;
                case 8: {
                    // Three RESERVE, One CREATE, three RESERVE (for executor) and One LAUNCH operation
                    Map<Protos.Offer.Operation.Type, Integer> expectedCounts = new HashMap<>();
                    expectedCounts.put(Protos.Offer.Operation.Type.RESERVE, 6);
                    expectedCounts.put(Protos.Offer.Operation.Type.CREATE, 1);
                    expectedCounts.put(Protos.Offer.Operation.Type.LAUNCH_GROUP, 1);
                    Map<Protos.Offer.Operation.Type, Integer> operationCounts = new HashMap<>();
                    for (Protos.Offer.Operation operation : operationSet) {
                        Integer count = operationCounts.get(operation.getType());
                        if (count == null) {
                            count = 1;
                        } else {
                            ++count;
                        }
                        operationCounts.put(operation.getType(), count);
                    }
                    if (expectedCounts.equals(operationCounts)) {
                        launch = true;
                    }
                    Assert.assertEquals(operationCounts.toString(), expectedCounts.keySet(), operationCounts.keySet());
                    break;
                }
                default:
                    break;
            }
        }

        Assert.assertTrue(operations.toString(), recovery);
        Assert.assertTrue(operations.toString(), launch);
        Assert.assertTrue(operations.toString(), unreserve);
    }

    @Test
    public void testConfigurationUpdate() throws Exception {
        // Get first Step associated with Task A-0
        Plan plan = getDeploymentPlan();
        Step stepTaskA0 = plan.getChildren().get(0).getChildren().get(0);
        Assert.assertTrue(stepTaskA0.isPending());

        // Offer sufficient Resource and wait for its acceptance
        Protos.Offer offer1 = getSufficientOfferForTaskA();
        defaultScheduler.offers(Arrays.asList(offer1));
        verify(mockSchedulerDriver, times(1)).acceptOffers(
                collectionThat(contains(offer1.getId())),
                operationsCaptor.capture(),
                any());

        Collection<Protos.Offer.Operation> operations = operationsCaptor.getValue();
        Protos.TaskID launchedTaskId = getTaskId(operations);

        // Send TASK_RUNNING status after the task is Starting (Mesos has been sent Launch)
        Assert.assertTrue(stepTaskA0.isStarting());
        statusUpdate(launchedTaskId, Protos.TaskState.TASK_RUNNING);

        // Wait for the Step to become Complete
        Assert.assertTrue(stepTaskA0.isComplete());
        Assert.assertEquals(Arrays.asList(Status.COMPLETE, Status.PENDING, Status.PENDING),
                getStepStatuses(plan));

        Assert.assertTrue(stepTaskA0.isComplete());
        Assert.assertEquals(0, getRecoveryPlan().getChildren().size());

        // Perform Configuration Update
        Capabilities.overrideCapabilities(getCapabilities());
        defaultScheduler = getScheduler(getServiceSpec(updatedPodA, podB));
        plan = getDeploymentPlan();
        stepTaskA0 = plan.getChildren().get(0).getChildren().get(0);
        Assert.assertEquals(Status.PENDING, stepTaskA0.getStatus());

        List<Protos.Resource> expectedResources = new ArrayList<>(getExpectedResources(operations));
        Protos.Resource neededAdditionalResource = ResourceTestUtils.getUnreservedCpus(UPDATED_TASK_A_CPU - TASK_A_CPU);
        expectedResources.add(neededAdditionalResource);


        // Start update Step: check behavior before and after reconciliation completes
        Protos.Offer insufficientOffer = OfferTestUtils.getCompleteOffer(neededAdditionalResource);

        // First attempt doesn't do anything because reconciliation hadn't completed yet
        defaultScheduler.offers(Arrays.asList(insufficientOffer));
        verify(mockSchedulerDriver, times(0)).killTask(any());
        verify(mockSchedulerDriver, times(1)).declineOffer(eq(insufficientOffer.getId()), any());
        Assert.assertEquals(Status.PENDING, stepTaskA0.getStatus());

        // Check that the scheduler had requested reconciliation of its sole task, then finish that reconciliation:
        verify(mockSchedulerDriver, times(1)).reconcileTasks(
                Arrays.asList(getTaskStatus(launchedTaskId, Protos.TaskState.TASK_RUNNING)));
        statusUpdate(launchedTaskId, Protos.TaskState.TASK_RUNNING);

        // Second attempt after reconciliation results in triggering task relaunch
        defaultScheduler.offers(Arrays.asList(insufficientOffer));
        verify(mockSchedulerDriver, times(1)).killTask(launchedTaskId);
        verify(mockSchedulerDriver, times(2)).declineOffer(eq(insufficientOffer.getId()), any());
        Assert.assertEquals(Status.PREPARED, stepTaskA0.getStatus());

        // Sent TASK_KILLED status
        statusUpdate(launchedTaskId, Protos.TaskState.TASK_KILLED);
        Assert.assertEquals(Status.PREPARED, stepTaskA0.getStatus());
        Assert.assertEquals(0, getRecoveryPlan().getChildren().size());

        Protos.Offer expectedOffer = OfferTestUtils.getCompleteOffer(expectedResources);
        defaultScheduler.offers(Arrays.asList(expectedOffer));
        verify(mockSchedulerDriver, times(1)).acceptOffers(
                collectionThat(contains(expectedOffer.getId())),
                operationsCaptor.capture(),
                any());
        Assert.assertTrue(stepTaskA0.isStarting());
        Assert.assertEquals(0, getRecoveryPlan().getChildren().size());

        operations = operationsCaptor.getValue();
        launchedTaskId = getTaskId(operations);
        // Send TASK_RUNNING status after the task is Starting (Mesos has been sent Launch)
        Assert.assertTrue(stepTaskA0.isStarting());
        statusUpdate(launchedTaskId, Protos.TaskState.TASK_RUNNING);
        Assert.assertTrue(stepTaskA0.isComplete());
    }

    @Test
    public void testInvalidConfigurationUpdate() throws Exception {
        // Launch A and B in original configuration
        testLaunchB();

        // Get initial target config UUID
        UUID targetConfigId = configStore.getTargetConfig();

        // Build new scheduler with invalid config (shrinking task count)
        Capabilities.overrideCapabilities(getCapabilities());
        defaultScheduler = getScheduler(getServiceSpec(podA, invalidPodB));

        // Ensure prior target configuration is still intact
        Assert.assertEquals(targetConfigId, configStore.getTargetConfig());

        Assert.assertEquals(1, getDeploymentPlan().getErrors().size());
        Assert.assertTrue(getDeploymentPlan().getErrors().get(0).contains("Transition: '2' => '1'"));
    }

    private static List<Protos.Resource> getExpectedResources(Collection<Protos.Offer.Operation> operations) {
        for (Protos.Offer.Operation operation : operations) {
            if (operation.getType().equals(Offer.Operation.Type.LAUNCH_GROUP)) {
                return Stream.concat(
                                operation.getLaunchGroup().getTaskGroup().getTasksList().stream()
                                    .flatMap(taskInfo -> taskInfo.getResourcesList().stream()),
                                operation.getLaunchGroup().getExecutor().getResourcesList().stream())
                        .collect(Collectors.toList());
            }
        }

        return Collections.emptyList();
    }

    @Test
    public void testTaskIpIsStoredOnInstall() throws Exception {
        install();

        // Verify the TaskIP (TaskInfo, strictly speaking) has been stored in the StateStore.
        Assert.assertTrue(StateStoreUtils.getTaskStatusFromProperty(
                stateStore, TASK_A_POD_NAME + "-0-" + TASK_A_NAME).isPresent());
        Assert.assertTrue(StateStoreUtils.getTaskStatusFromProperty(
                stateStore, TASK_B_POD_NAME + "-0-" + TASK_B_NAME).isPresent());
    }

    @Test
    public void testTaskIpIsUpdatedOnStatusUpdate() throws Exception {
        List<Protos.TaskID> taskIds = install();
        String updateIp = "1.1.1.1";

        // Verify the TaskIP (TaskInfo, strictly speaking) has been stored in the StateStore.
        Assert.assertTrue(StateStoreUtils.getTaskStatusFromProperty(
                stateStore, TASK_A_POD_NAME + "-0-" + TASK_A_NAME).isPresent());
        Assert.assertTrue(StateStoreUtils.getTaskStatusFromProperty(
                stateStore, TASK_B_POD_NAME + "-0-" + TASK_B_NAME).isPresent());

        Protos.TaskStatus update = Protos.TaskStatus.newBuilder(
                getTaskStatus(taskIds.get(0), Protos.TaskState.TASK_STAGING))
                .setContainerStatus(Protos.ContainerStatus.newBuilder()
                        .addNetworkInfos(Protos.NetworkInfo.newBuilder()
                            .addIpAddresses(Protos.NetworkInfo.IPAddress.newBuilder()
                                .setIpAddress(updateIp))))
                .build();
        defaultScheduler.status(update);

        // Verify the TaskStatus was updated.
        Assert.assertEquals(updateIp,
                StateStoreUtils.getTaskStatusFromProperty(stateStore, TASK_A_POD_NAME + "-0-" + TASK_A_NAME).get()
                        .getContainerStatus().getNetworkInfos(0).getIpAddresses(0).getIpAddress());
    }

    @Test
    public void testTaskIpIsNotOverwrittenByEmptyOnUpdate() throws Exception {
        List<Protos.TaskID> taskIds = install();

        // Verify the TaskIP (TaskInfo, strictly speaking) has been stored in the StateStore.
        Assert.assertTrue(StateStoreUtils.getTaskStatusFromProperty(
                stateStore, TASK_A_POD_NAME + "-0-" + TASK_A_NAME).isPresent());
        Assert.assertTrue(StateStoreUtils.getTaskStatusFromProperty(
                stateStore, TASK_B_POD_NAME + "-0-" + TASK_B_NAME).isPresent());

        Protos.TaskStatus update = Protos.TaskStatus.newBuilder(
                getTaskStatus(taskIds.get(0), Protos.TaskState.TASK_STAGING))
                .setContainerStatus(Protos.ContainerStatus.newBuilder()
                        .addNetworkInfos(Protos.NetworkInfo.newBuilder()))
                .build();
        defaultScheduler.status(update);

        // Verify the TaskStatus was NOT updated.
        Assert.assertEquals(TASK_IP,
                StateStoreUtils.getTaskStatusFromProperty(
                        stateStore, TASK_A_POD_NAME + "-0-" + TASK_A_NAME).get()
                                .getContainerStatus().getNetworkInfos(0).getIpAddresses(0).getIpAddress());
    }

    @Test
    public void testDecommissionPlanCustomization() throws Exception {
        AtomicBoolean decommissionPlanCustomized = new AtomicBoolean(false);
        PlanCustomizer planCustomizer = new PlanCustomizer() {
            @Override
            public Plan updatePlan(Plan plan) {
                if (plan.isDecommissionPlan()) {
                    decommissionPlanCustomized.set(true);
                }

                return plan;
            }
        };

        // Launches the first instance of POD-B
        testLaunchB();

        // Launch the second instance of POD-B
        installStep(1, 1, getSufficientOfferForTaskB(), Status.PENDING);
        Assert.assertEquals(
                Arrays.asList(Status.COMPLETE, Status.COMPLETE, Status.COMPLETE),
                getStepStatuses(getDeploymentPlan()));

        // Construct POD-B which scales in by 1 pod instance
        PodSpec scaledInPodB = DefaultPodSpec.newBuilder(podB)
                .count(TASK_B_COUNT - 1)
                .allowDecommission(true)
                .build();

        DefaultScheduler.newBuilder(
                getServiceSpec(podA, scaledInPodB), SchedulerConfigTestUtils.getTestSchedulerConfig(), persister)
                .setPlanCustomizer(planCustomizer)
                .build()
                .start();

        Assert.assertTrue(decommissionPlanCustomized.get());
    }

    private static int countOperationType(
            Protos.Offer.Operation.Type operationType, Collection<Protos.Offer.Operation> operations) {
        int count = 0;
        for (Protos.Offer.Operation operation : operations) {
            if (operation.getType().equals(operationType)) {
                count++;
            }
        }
        return count;
    }

    private static Protos.TaskInfo getTask(Collection<Protos.Offer.Operation> operations) {
        for (Protos.Offer.Operation operation : operations) {
            if (operation.getType().equals(Offer.Operation.Type.LAUNCH_GROUP)) {
                return operation.getLaunchGroup().getTaskGroup().getTasks(0);
            }
        }

        return null;
    }

    private static Protos.TaskID getTaskId(Collection<Protos.Offer.Operation> operations) {
        return getTask(operations).getTaskId();
    }

    private static Protos.TaskStatus getTaskStatus(Protos.TaskID taskID, Protos.TaskState state) {
        return Protos.TaskStatus.newBuilder()
                .setTaskId(taskID)
                .setState(state)
                .setContainerStatus(Protos.ContainerStatus.newBuilder()
                    .addNetworkInfos(Protos.NetworkInfo.newBuilder()
                        .addIpAddresses(Protos.NetworkInfo.IPAddress.newBuilder()
                            .setIpAddress(TASK_IP))))
                .build();
    }

    private static Protos.Offer getInsufficientOfferForTaskA(UUID offerId) {
        return Protos.Offer.newBuilder()
                .setId(Protos.OfferID.newBuilder().setValue(offerId.toString()).build())
                .setFrameworkId(TestConstants.FRAMEWORK_ID)
                .setSlaveId(TestConstants.AGENT_ID)
                .setHostname(TestConstants.HOSTNAME)
                .addAllResources(
                        Arrays.asList(
                                ResourceTestUtils.getUnreservedCpus(TASK_A_CPU / 2.0),
                                ResourceTestUtils.getUnreservedMem(TASK_A_MEM / 2.0)))
                .build();
    }

    private static Protos.Offer getSufficientOfferForTaskA() {
        return Protos.Offer.newBuilder()
                .setId(Protos.OfferID.newBuilder().setValue(UUID.randomUUID().toString()).build())
                .setFrameworkId(TestConstants.FRAMEWORK_ID)
                .setSlaveId(TestConstants.AGENT_ID)
                .setHostname(TestConstants.HOSTNAME)
                .addAllResources(
                        Arrays.asList(
                                ResourceTestUtils.getUnreservedCpus(TASK_A_CPU + 0.1),
                                ResourceTestUtils.getUnreservedMem(TASK_A_MEM + 32),
                                ResourceTestUtils.getUnreservedDisk(TASK_A_DISK + 256)))
                .build();
    }

    private static Protos.Offer getSufficientOfferForTaskB() {
        return Protos.Offer.newBuilder()
                .setId(Protos.OfferID.newBuilder().setValue(UUID.randomUUID().toString()).build())
                .setFrameworkId(TestConstants.FRAMEWORK_ID)
                .setSlaveId(TestConstants.AGENT_ID)
                .setHostname(TestConstants.HOSTNAME)
                .addAllResources(
                        Arrays.asList(
                                ResourceTestUtils.getUnreservedCpus(TASK_B_CPU + 0.1),
                                ResourceTestUtils.getUnreservedMem(TASK_B_MEM + 32),
                                ResourceTestUtils.getUnreservedDisk(TASK_B_DISK + 256)))
                .build();
    }

    private static <T> Collection<T> collectionThat(final Matcher<Iterable<? extends T>> matcher) {
        return Matchers.argThat(new BaseMatcher<Collection<T>>() {
            @Override
            public boolean matches(Object item) {
                return matcher.matches(item);
            }

            @Override
            public void describeTo(Description description) {
                matcher.describeTo(description);
            }
        });
    }

    private Protos.TaskID installStep(int phaseIndex, int stepIndex, Protos.Offer offer, Status expectedStepStatus)
            throws Exception {
        // Get first Step associated with Task A-0
        Step step = getDeploymentPlan().getChildren().get(phaseIndex).getChildren().get(stepIndex);
        Assert.assertEquals(expectedStepStatus, step.getStatus());

        // Offer sufficient Resource and wait for its acceptance
        defaultScheduler.offers(Arrays.asList(offer));
        verify(mockSchedulerDriver, times(1)).acceptOffers(
                collectionThat(contains(offer.getId())),
                operationsCaptor.capture(),
                any());

        // Verify operations:
        Collection<Protos.Offer.Operation> operations = operationsCaptor.getValue();
        Assert.assertEquals(8, operations.size());
        Assert.assertEquals(6, countOperationType(Protos.Offer.Operation.Type.RESERVE, operations));
        Assert.assertEquals(1, countOperationType(Protos.Offer.Operation.Type.CREATE, operations));
        Assert.assertEquals(1, countOperationType(Offer.Operation.Type.LAUNCH_GROUP, operations));
        Assert.assertTrue(step.isStarting());

        // Sent TASK_RUNNING status
        Protos.TaskID taskId = getTaskId(operations);
        statusUpdate(taskId, Protos.TaskState.TASK_RUNNING);

        // Wait for the Step to become Complete
        Assert.assertTrue(step.isComplete());

        return taskId;
    }

    private void statusUpdate(Protos.TaskID launchedTaskId, Protos.TaskState state) {
        Protos.TaskStatus runningStatus = getTaskStatus(launchedTaskId, state);
        defaultScheduler.status(runningStatus);
    }

    /**
     * Installs the service.
     */
    private List<Protos.TaskID> install() throws Exception {
        List<Protos.TaskID> taskIds = new ArrayList<>();

        taskIds.add(installStep(0, 0, getSufficientOfferForTaskA(), Status.PENDING));
        taskIds.add(installStep(1, 0, getSufficientOfferForTaskB(), Status.PENDING));
        taskIds.add(installStep(1, 1, getSufficientOfferForTaskB(), Status.PENDING));

        Assert.assertTrue(getDeploymentPlan().isComplete());
        Assert.assertEquals(Arrays.asList(Status.COMPLETE, Status.COMPLETE, Status.COMPLETE),
                getStepStatuses(getDeploymentPlan()));
        Assert.assertTrue(defaultScheduler.getPlanCoordinator().getCandidates().isEmpty());

        return taskIds;
    }

    private DefaultScheduler getScheduler(ServiceSpec serviceSpec) throws PersisterException {
        AbstractScheduler scheduler = DefaultScheduler.newBuilder(
                serviceSpec, SchedulerConfigTestUtils.getTestSchedulerConfig(), persister)
                .build()
                .start();
        scheduler.registered(false);
        return (DefaultScheduler) scheduler;
    }

    private Plan getDeploymentPlan() throws Exception {
        return getPlan(Constants.DEPLOY_PLAN_NAME);
    }

    private Plan getRecoveryPlan() throws Exception {
        return getPlan(Constants.RECOVERY_PLAN_NAME);
    }

    private Plan getPlan(String planName) throws Exception {
        for (PlanManager planManager : defaultScheduler.getPlanCoordinator().getPlanManagers()) {
            if (planManager.getPlan().getName().equals(planName)) {
                return planManager.getPlan();
            }
        }
        throw new IllegalStateException(String.format(
                "No %s plan found: %s", planName, defaultScheduler.getPlanCoordinator().getPlanManagers()));
    }

    private static List<Status> getStepStatuses(Plan plan) {
        return plan.getChildren().stream()
                .flatMap(phase -> phase.getChildren().stream())
                .map(Element::getStatus)
                .collect(Collectors.toList());
    }
}
