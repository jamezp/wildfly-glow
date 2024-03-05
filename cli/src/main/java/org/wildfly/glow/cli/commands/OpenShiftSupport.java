/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2024 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.glow.cli.commands;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HTTPGetAction;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import io.fabric8.openshift.api.model.ImageLookupPolicy;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamBuilder;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.api.model.RouteTargetReference;
import io.fabric8.openshift.api.model.TLSConfig;
import io.fabric8.openshift.client.OpenShiftClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jboss.galleon.util.ZipUtils;
import org.wildfly.glow.AddOn;
import org.wildfly.glow.GlowMessageWriter;
import org.wildfly.glow.Layer;
import org.wildfly.glow.deployment.openshift.api.Deployer;

/**
 *
 * @author jdenise
 */
class OpenShiftSupport {

    private static void createAppDeployment(GlowMessageWriter writer, Path target, OpenShiftClient osClient, String name, Map<String, String> env, boolean ha) throws Exception {
        writer.info("Deploying application image on OpenShift");
        Map<String, String> labels = new HashMap<>();
        labels.put(Deployer.LABEL, name);
        ContainerPort port = new ContainerPort();
        port.setContainerPort(8080);
        port.setName("http");
        port.setProtocol("TCP");

        ContainerPort portAdmin = new ContainerPort();
        portAdmin.setContainerPort(9990);
        portAdmin.setName("admin");
        portAdmin.setProtocol("TCP");

        List<ContainerPort> ports = new ArrayList<>();
        ports.add(port);
        ports.add(portAdmin);
        List<EnvVar> vars = new ArrayList<>();
        for (Entry<String, String> entry : env.entrySet()) {
            vars.add(new EnvVar().toBuilder().withName(entry.getKey()).withValue(entry.getValue()).build());
        }
        Container container = new Container();
        container.setName(name);
        container.setImage(name + ":latest");
        container.setPorts(ports);
        container.setEnv(vars);
        container.setImagePullPolicy("IfNotPresent");
        Probe readinessProbe = new Probe();
        HTTPGetAction getAction = new HTTPGetAction();
        getAction.setPath("/health/ready");
        IntOrString pp = new IntOrString("admin");
        getAction.setPort(pp);
        getAction.setScheme("HTTP");
        readinessProbe.setHttpGet(getAction);
        readinessProbe.setTimeoutSeconds(1);
        readinessProbe.setPeriodSeconds(10);
        readinessProbe.setSuccessThreshold(1);
        readinessProbe.setFailureThreshold(3);

        container.setReadinessProbe(readinessProbe);
        container.setTerminationMessagePath("/dev/termination-log");

        Probe livenessProbe = new Probe();
        HTTPGetAction getAction2 = new HTTPGetAction();
        getAction2.setPath("/health/live");
        IntOrString pp2 = new IntOrString("admin");
        getAction2.setPort(pp2);
        getAction2.setScheme("HTTP");
        livenessProbe.setHttpGet(getAction);
        livenessProbe.setTimeoutSeconds(1);
        livenessProbe.setPeriodSeconds(10);
        livenessProbe.setSuccessThreshold(1);
        livenessProbe.setFailureThreshold(3);
        container.setLivenessProbe(livenessProbe);

        Deployment deployment = new DeploymentBuilder().withNewMetadata().withName(name).endMetadata().
                withNewSpec().withReplicas(ha ? 2 : 1).
                withNewSelector().withMatchLabels(labels).endSelector().
                withNewTemplate().withNewMetadata().withLabels(labels).endMetadata().withNewSpec().
                withContainers(container).withRestartPolicy("Always").
                endSpec().endTemplate().withNewStrategy().withType("RollingUpdate").endStrategy().endSpec().build();
        osClient.resources(Deployment.class).resource(deployment).createOr(NonDeletingOperation::update);
        Files.write(target.resolve(name + "-deployment.yaml"), Serialization.asYaml(deployment).getBytes());
        IntOrString v = new IntOrString();
        v.setValue(8080);
        Service service = new ServiceBuilder().withNewMetadata().withName(name).endMetadata().
                withNewSpec().withPorts(new ServicePort().toBuilder().withProtocol("TCP").
                        withPort(8080).
                        withTargetPort(v).build()).withType("ClusterIP").withSessionAffinity("None").withSelector(labels).endSpec().build();
        osClient.services().resource(service).createOr(NonDeletingOperation::update);
        Files.write(target.resolve(name + "-service.yaml"), Serialization.asYaml(service).getBytes());

        writer.info("Waiting until the application is ready ...");
        osClient.resources(Deployment.class).resource(deployment).waitUntilReady(5, TimeUnit.MINUTES);
    }

    static void deploy(GlowMessageWriter writer, Path target, String appName, Map<String, String> env, Set<Layer> layers, Set<AddOn> addOns, boolean ha,
            Map<String, String> extraEnv, Set<String> disabledDeployers) throws Exception {
        Map<String, String> actualEnv = new TreeMap<>();
        OpenShiftClient osClient = new KubernetesClientBuilder().build().adapt(OpenShiftClient.class);
        writer.info("\nConnected to OpenShift cluster");
        // First create the future route to the application, can be needed by deployers
        Route route = new RouteBuilder().withNewMetadata().withName(appName).
                endMetadata().withNewSpec().
                withTo(new RouteTargetReference("Service", appName, 100)).
                withTls(new TLSConfig().toBuilder().withTermination("edge").
                        withInsecureEdgeTerminationPolicy("Redirect").build()).endSpec().build();
        osClient.routes().resource(route).createOr(NonDeletingOperation::update);
        Files.write(target.resolve(appName + "-route.yaml"), Serialization.asYaml(route).getBytes());
        String host = osClient.routes().resource(route).get().getSpec().getHost();
        // Done route creation
        Map<String, Deployer> existingDeployers = new HashMap<>();

        for (Deployer d : ServiceLoader.load(Deployer.class)) {
            existingDeployers.put(d.getName(), d);
        }
        for (String disabled : disabledDeployers) {
            if (!"ALL".equals(disabled)) {
                if (!existingDeployers.containsKey(disabled)) {
                    throw new Exception("Invalid deployer to disable: " + disabled);
                }
            }
        }
        for (Deployer d : existingDeployers.values()) {
            boolean deployed = false;
            boolean isDisabled = isDisabled(d.getName(), disabledDeployers);
            for (Layer l : layers) {
                if (d.getSupportedLayers().contains(l.getName())) {
                    deployed = true;
                    if (!isDisabled) {
                        writer.info("Found deployer " + d.getName() + " for " + l.getName());
                    } else {
                        writer.warn("The deployer " + d.getName() + " has been disabled");
                    }
                    actualEnv.putAll(isDisabled ? d.disabledDeploy(host, appName, l.getName(), env) : d.deploy(writer, target, osClient, env, host, appName, l.getName()));
                    break;
                }
            }
            if (!deployed) {
                for (AddOn ao : addOns) {
                    if (ao.getFamily().equals(d.getSupportedAddOnFamily())
                            && d.getSupportedAddOns().contains(ao.getName())) {
                        if (!isDisabled) {
                            writer.info("Found deployer " + d.getName() + " for " + ao.getName());
                        } else {
                            writer.warn("The deployer " + d.getName() + " has been disabled");
                        }
                        actualEnv.putAll(isDisabled ? d.disabledDeploy(host, appName, ao.getName(), env) : d.deploy(writer, target, osClient, env, host, appName, ao.getName()));
                        break;
                    }
                }
            }
        }

        createBuild(writer, target, osClient, appName);

        actualEnv.putAll(extraEnv);
        if (!actualEnv.isEmpty()) {
            if (!disabledDeployers.isEmpty()) {
                writer.warn("\nThe following environment variables have been set in the " + appName + " deployment. WARN: Some of them need possibly to be updated in the deployment:");
            } else {
                writer.warn("\nThe following environment variables have been set in the " + appName + " deployment:");
            }
            for (Entry<String, String> entry : actualEnv.entrySet()) {
                writer.warn(entry.getKey() + "=" + entry.getValue());
            }
        }
        createAppDeployment(writer, target, osClient, appName, actualEnv, ha);
        writer.info("\nApplication route: https://" + host + ("ROOT.war".equals(appName) ? "" : "/" + appName));
    }

    private static boolean isDisabled(String name, Set<String> disabledDeployers) {
        return disabledDeployers.contains("ALL") || disabledDeployers.contains(name);
    }

    static void createBuild(GlowMessageWriter writer, Path target, OpenShiftClient osClient, String name) throws Exception {
        // zip deployment and provisioning.xml to be pushed to OpenShift
        Path file = Paths.get("openshiftApp.zip");
        if (Files.exists(file)) {
            Files.delete(file);
        }
        file.toFile().deleteOnExit();
        ZipUtils.zip(target, file);
        writer.info("\nCreating and starting application image build on OpenShift (this can take up to few minutes)...");
        ImageStream stream = new ImageStreamBuilder().withNewMetadata().withName(name).
                endMetadata().withNewSpec().withLookupPolicy(new ImageLookupPolicy(Boolean.TRUE)).endSpec().build();
        osClient.imageStreams().resource(stream).createOr(NonDeletingOperation::update);
        Files.write(target.resolve(name + "-image-stream.yaml"), Serialization.asYaml(stream).getBytes());
        BuildConfigBuilder builder = new BuildConfigBuilder();
        ObjectReference ref = new ObjectReference();
        ref.setKind("ImageStreamTag");
        ref.setName(name + ":latest");
        BuildConfig buildConfig = builder.
                withNewMetadata().withName(name + "-build").endMetadata().withNewSpec().
                withNewOutput().
                withNewTo().
                withKind("ImageStreamTag").
                withName(name + ":latest").endTo().
                endOutput().withNewStrategy().withNewSourceStrategy().withNewFrom().withKind("DockerImage").
                withName("quay.io/wildfly/wildfly-s2i:latest").endFrom().
                withIncremental(true).
                withEnv(new EnvVar().toBuilder().withName("GALLEON_USE_LOCAL_FILE").withValue("true").build()).
                endSourceStrategy().endStrategy().withNewSource().
                withType("Binary").endSource().endSpec().build();
        osClient.buildConfigs().resource(buildConfig).createOr(NonDeletingOperation::update);
        Files.write(target.resolve(name + "-build-config.yaml"), Serialization.asYaml(buildConfig).getBytes());

        Build build = osClient.buildConfigs().withName(name + "-build").instantiateBinary().fromFile(file.toFile());
        CountDownLatch latch = new CountDownLatch(1);
        try (Watch watcher = osClient.builds().withName(build.getMetadata().getName()).watch(getBuildWatcher(writer, latch))) {
            latch.await();
        }
    }

    private static Watcher<Build> getBuildWatcher(GlowMessageWriter writer, final CountDownLatch latch) {
        return new Watcher<Build>() {
            @Override
            public void eventReceived(Action action, Build build) {
                //buildHolder.set(build);
                String phase = build.getStatus().getPhase();
                if ("Running".equals(phase)) {
                    writer.info("Build is running...");
                }
                if ("Complete".equals(phase)) {
                    writer.info("Build is complete.");
                    latch.countDown();
                }
            }

            @Override
            public void onClose(WatcherException cause) {
            }
        };
    }
}