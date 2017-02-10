package fk.prof.backend.request.profile.parser;

import fk.prof.backend.exception.AggregationFailure;
import fk.prof.backend.model.profile.RecordedProfileHeader;
import fk.prof.backend.request.CompositeByteBufInputStream;
import recording.Recorder;

import java.io.IOException;
import java.util.zip.Adler32;

public class RecordedProfileHeaderParser {
  private int encodingVersion;
  private Recorder.RecordingHeader recordingHeader = null;

  private Adler32 checksum = new Adler32();
  private boolean parsed = false;
  private int maxMessageSize;

  public RecordedProfileHeaderParser(int maxMessageSize) {
    this.maxMessageSize = maxMessageSize;
  }

  /**
   * Returns true if all fields of header have been read and checksum validated, false otherwise
   *
   * @return returns if header has been parsed or not
   */
  public boolean isParsed() {
    return this.parsed;
  }

  /**
   * Returns {@link RecordedProfileHeader} if {@link #isParsed()} is true, null otherwise
   *
   * @return
   */
  public RecordedProfileHeader get() {
    if (!this.parsed) {
      return null;
    }
    return new RecordedProfileHeader(this.encodingVersion, this.recordingHeader);
  }

  /**
   * Reads buffer and updates internal state with parsed fields.
   * @param in
   */
  public void parse(CompositeByteBufInputStream in) throws AggregationFailure {
    try {
      if(recordingHeader == null) {
        in.mark(maxMessageSize);
        encodingVersion = MessageParser.readRawVariantInt(in, "encodingVersion");
        recordingHeader = MessageParser.readDelimited(Recorder.RecordingHeader.parser(), in, maxMessageSize, "recording header");
        in.updateChecksumSinceMarked(checksum);
      }
      in.mark(maxMessageSize);
      int checksumValue = MessageParser.readRawVariantInt(in, "headerChecksumValue");
      if(checksumValue != ((int)checksum.getValue())) {
        throw new AggregationFailure("Checksum of header does not match");
      }
      parsed = true;
    }
    catch (UnexpectedEOFException e) {
      try {
        in.reset();
      }
      catch (IOException resetEx) {
        throw new AggregationFailure(resetEx);
      }
    }
    catch (IOException e) {
      throw new AggregationFailure(e);
    }
  }
}
