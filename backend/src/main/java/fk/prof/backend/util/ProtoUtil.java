package fk.prof.backend.util;

import fk.prof.aggregation.proto.AggregatedProfileModel;
import fk.prof.backend.proto.BackendDTO;
import recording.Recorder;

public class ProtoUtil {
  public static AggregatedProfileModel.WorkType mapRecorderToAggregatorWorkType(Recorder.WorkType recorderWorkType) {
    return AggregatedProfileModel.WorkType.forNumber(recorderWorkType.getNumber());
  }

  public static BackendDTO.ProcessGroup mapRecorderToBackendProcessGroup(Recorder.ProcessGroup recorderProcessGroup) {
    return BackendDTO.ProcessGroup.newBuilder()
        .setAppId(recorderProcessGroup.getAppId())
        .setCluster(recorderProcessGroup.getCluster())
        .setProcName(recorderProcessGroup.getProcName())
        .build();
  }

  public static String processGroupCompactRepr(BackendDTO.ProcessGroup processGroup) {
    return String.format("%s,%s,%s", processGroup.getAppId(), processGroup.getCluster(), processGroup.getProcName());
  }
}