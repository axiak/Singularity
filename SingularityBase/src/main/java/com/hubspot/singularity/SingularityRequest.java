package com.hubspot.singularity;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.mesos.Resources;

public class SingularityRequest {

  private final String name;

  private final String executor;
  private final Resources resources;

  private final String schedule;

  private final Integer instances;
  private final Boolean rackSensitive;
  private final Boolean daemon;

  private final String command;
  private final Map<String, String> env;
  private final List<String> uris;
  private final Object executorData;

  @JsonCreator
  public SingularityRequest(@JsonProperty("command") String command, @JsonProperty("name") String name, @JsonProperty("executor") String executor, @JsonProperty("resources") Resources resources, @JsonProperty("schedule") String schedule,
      @JsonProperty("instances") Integer instances, @JsonProperty("daemon") Boolean daemon, @JsonProperty("env") Map<String, String> env, @JsonProperty("uris") List<String> uris,
      @JsonProperty("executorData") Object executorData, @JsonProperty("rackSensitive") Boolean rackSensitive) {
    this.command = command;
    this.name = name;
    this.resources = resources;
    this.executor = executor;
    this.schedule = schedule;
    this.daemon = daemon;
    this.instances = instances;
    this.rackSensitive = rackSensitive;
    
    this.env = env;
    this.uris = uris;
    this.executorData = executorData;
  }

  public Map<String, String> getEnv() {
    return env;
  }

  public List<String> getUris() {
    return uris;
  }

  public Object getExecutorData() {
    return executorData;
  }
  
  public Boolean getRackSensitive() {
    return rackSensitive;
  }

  public byte[] getRequestData(ObjectMapper objectMapper) throws Exception {
    return objectMapper.writeValueAsBytes(this);
  }

  public static SingularityRequest getRequestFromData(byte[] request, ObjectMapper objectMapper) throws Exception {
    return objectMapper.readValue(request, SingularityRequest.class);
  }

  public Integer getInstances() {
    return instances;
  }

  public Boolean getDaemon() {
    return daemon;
  }

  @JsonIgnore
  public boolean isRackSensitive() {
    return (rackSensitive != null && rackSensitive.booleanValue());
  }
  
  @JsonIgnore
  public boolean alwaysRunning() {
    return (daemon == null || daemon.booleanValue()) && !isScheduled();
  }

  @JsonIgnore
  public boolean isScheduled() {
    return schedule != null;
  }

  public String getSchedule() {
    return schedule;
  }

  public String getName() {
    return name;
  }

  public String getExecutor() {
    return executor;
  }

  public Resources getResources() {
    return resources;
  }

  public String getCommand() {
    return command;
  }

  @Override
  public String toString() {
    return "SingularityRequest [name=" + name + ", executor=" + executor + ", resources=" + resources + ", schedule=" + schedule + ", instances=" + instances + ", rackSensitive=" + rackSensitive + ", daemon=" + daemon + ", command="
        + command + ", env=" + env + ", uris=" + uris + ", executorData=" + executorData + "]";
  }
  
}
