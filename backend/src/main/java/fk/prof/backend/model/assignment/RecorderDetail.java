package fk.prof.backend.model.assignment;

import com.google.common.base.Preconditions;
import recording.Recorder;

public class RecorderDetail {
  private static final double NANOSECONDS_IN_SECOND = Math.pow(10, 9);

  private final RecorderIdentifier recorderIdentifier;
  private final long thresholdForDefunctRecorderInNanos;

  private long lastReportedTick = 0;
  private Long lastReportedTime = null;
  private Recorder.WorkResponse currentWorkResponse;

  public RecorderDetail(RecorderIdentifier recorderIdentifier, int thresholdForDefunctRecorderInSecs) {
    this.recorderIdentifier = Preconditions.checkNotNull(recorderIdentifier);
    this.thresholdForDefunctRecorderInNanos = (long)(thresholdForDefunctRecorderInSecs * NANOSECONDS_IN_SECOND);
  }

  public synchronized boolean receivePoll(Recorder.PollReq pollReq) {
    long currTick = pollReq.getRecorderInfo().getRecorderTick();
    boolean timeUpdated = false;
    //NOTE: this is assuming that curr tick is always unsigned and does not wrap around.
    //here curr tick = 0 has a special meaning to reset the tick.
    if(currTick == 0 || this.lastReportedTick <= currTick) {
      this.lastReportedTick = currTick;
      if(currTick > 0) {
        this.lastReportedTime = System.nanoTime();
        timeUpdated = true;
      }
      this.currentWorkResponse = pollReq.getWorkLastIssued();
    }
    return timeUpdated;
  }

  public boolean isDefunct() {
    return lastReportedTime == null ||
        ((System.nanoTime() - lastReportedTime) > thresholdForDefunctRecorderInNanos);
  }

  public boolean canAcceptWork() {
    if (!isDefunct() &&
        (currentWorkResponse.getWorkId() == 0 || Recorder.WorkResponse.WorkState.complete.equals(currentWorkResponse.getWorkState()))) {
      return true;
    }
    return false;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof RecorderDetail)) {
      return false;
    }

    RecorderDetail other = (RecorderDetail) o;
    return this.recorderIdentifier.equals(other.recorderIdentifier);
  }

  @Override
  public int hashCode() {
    final int PRIME = 31;
    int result = 1;
    result = result * PRIME + this.recorderIdentifier.hashCode();
    return result;
  }
}