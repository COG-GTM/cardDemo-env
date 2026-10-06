package com.carddemo.legacy.egress;

import com.carddemo.legacy.codec.CopybookCodec;
import com.carddemo.legacy.codec.CopybookRecord;
import com.carddemo.legacy.codec.SignEncoding;
import com.carddemo.legacy.copybook.CopybookLayout;
import com.carddemo.legacy.copybook.CopybookParser;
import com.carddemo.legacy.ingress.Account;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the updated account master back for the COBOL side as a new generation of
 * {@code AWS.M2.CARDDEMO.ACCTDATA.XFER} (copybook CVACT01Y, GDG LIMIT(5) SCRATCH per DEFGDGX).
 */
public final class AcctDataXferEgress {
  public static final String DSN = "AWS.M2.CARDDEMO.ACCTDATA.XFER";
  public static final int GDG_LIMIT = 5;

  private final CopybookLayout layout;
  private final CopybookCodec codec;

  public AcctDataXferEgress(CopybookParser copybooks, SignEncoding signEncoding) {
    this.layout = copybooks.layout(Account.COPYBOOK);
    this.codec = new CopybookCodec(layout, signEncoding);
  }

  /** Writes {@code accounts} as fresh records, in order, and returns the new generation's path. */
  public Path write(Path datasetsDirectory, List<Account> accounts) throws IOException {
    return write(datasetsDirectory, accounts, List.of());
  }

  /**
   * Writes {@code accounts} in order. An account whose id appears in {@code sourceImages} (records
   * from {@code LegacyFileIngress#accountImages}) is overlaid on that record, so FILLER and
   * unchanged fields are written back byte-for-byte, as the COBOL step does.
   */
  public Path write(Path datasetsDirectory, List<Account> accounts, List<CopybookRecord> sourceImages)
      throws IOException {
    Map<Long, CopybookRecord> images = new HashMap<>();
    for (CopybookRecord image : sourceImages) {
      images.put(image.longValue("ACCT-ID"), image);
    }
    List<CopybookRecord> records =
        accounts.stream()
            .map(a -> images.containsKey(a.acctId()) ? a.applyTo(images.get(a.acctId())) : a.toRecord(layout))
            .toList();
    return new GenerationDataGroup(datasetsDirectory, DSN, GDG_LIMIT).writeNewGeneration(codec.encodeAll(records));
  }
}
