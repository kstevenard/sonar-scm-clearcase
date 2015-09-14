/*
 * SonarQube :: Plugins :: SCM :: ClearCase
 * Copyright (C) 2014 SonarSource
 * sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.scm.clearcase;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultFileSystem;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.scm.BlameCommand.BlameInput;
import org.sonar.api.batch.scm.BlameCommand.BlameOutput;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.command.Command;
import org.sonar.api.utils.command.CommandExecutor;
import org.sonar.api.utils.command.StreamConsumer;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class ClearCaseBlameCommandTest {

  @Rule
  public UTCRule utcRule = new UTCRule();

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private DefaultFileSystem fs;
  private File baseDir;
  private BlameInput input;

  @Before
  public void prepare() throws IOException {
    baseDir = temp.newFolder();
    fs = new DefaultFileSystem();
    fs.setBaseDir(baseDir);
    input = mock(BlameInput.class);
    when(input.fileSystem()).thenReturn(fs);
  }

  @Test
  public void testParsingOfOutput() throws IOException {
    File source = new File(baseDir, "src/foo.xoo");
    FileUtils.write(source, "sample content");
    DefaultInputFile inputFile = new DefaultInputFile("foo", "src/foo.xoo").setLines(3).setAbsolutePath(new File(baseDir, "src/foo.xoo").getAbsolutePath());
    fs.add(inputFile);

    BlameOutput result = mock(BlameOutput.class);
    CommandExecutor commandExecutor = mock(CommandExecutor.class);

    when(commandExecutor.execute(any(Command.class), any(StreamConsumer.class), any(StreamConsumer.class), anyLong())).thenAnswer(new Answer<Integer>() {

      @Override
      public Integer answer(InvocationOnMock invocation) throws Throwable {
        StreamConsumer outConsumer = (StreamConsumer) invocation.getArguments()[1];
        outConsumer.consumeLine("VERSION:7@@@USER:Jeremie Lagarde@@@DATE:20081026.162644@@@foo");
        outConsumer.consumeLine("VERSION:7@@@USER:Jeremie Lagarde@@@DATE:20081026.162644@@@");
        outConsumer.consumeLine("VERSION:5@@@USER:Evgeny Mandrikov@@@DATE:20081017.114150@@@bar");
        return 0;
      }
    });

    when(input.filesToBlame()).thenReturn(Arrays.<InputFile>asList(inputFile));
    new ClearCaseBlameCommand(commandExecutor).blame(input, result);
    verify(result).blameResult(inputFile,
      Arrays.asList(new BlameLine().date(DateUtils.parseDateTime("2008-10-26T16:26:44+0000")).revision("7").author("Jeremie Lagarde"),
        new BlameLine().date(DateUtils.parseDateTime("2008-10-26T16:26:44+0000")).revision("7").author("Jeremie Lagarde"),
        new BlameLine().date(DateUtils.parseDateTime("2008-10-17T11:41:50+0000")).revision("5").author("Evgeny Mandrikov")));
  }

  @Test
  public void testAddMissingLastLine() throws IOException {
    File source = new File(baseDir, "src/foo.xoo");
    FileUtils.write(source, "sample content");
    DefaultInputFile inputFile = new DefaultInputFile("foo", "src/foo.xoo").setLines(4).setAbsolutePath(new File(baseDir, "src/foo.xoo").getAbsolutePath());
    fs.add(inputFile);

    BlameOutput result = mock(BlameOutput.class);
    CommandExecutor commandExecutor = mock(CommandExecutor.class);

    when(commandExecutor.execute(any(Command.class), any(StreamConsumer.class), any(StreamConsumer.class), anyLong())).thenAnswer(new Answer<Integer>() {

      @Override
      public Integer answer(InvocationOnMock invocation) throws Throwable {
        StreamConsumer outConsumer = (StreamConsumer) invocation.getArguments()[1];
        outConsumer.consumeLine("VERSION:7@@@USER:Jeremie Lagarde@@@DATE:20081026.162644@@@foo");
        outConsumer.consumeLine("VERSION:7@@@USER:Jeremie Lagarde@@@DATE:20081026.162644@@@");
        outConsumer.consumeLine("VERSION:5@@@USER:Evgeny Mandrikov@@@DATE:20081017.114150@@@bar");
        // ClearCase doesn't blame last empty line
        return 0;
      }
    });

    when(input.filesToBlame()).thenReturn(Arrays.<InputFile>asList(inputFile));
    new ClearCaseBlameCommand(commandExecutor).blame(input, result);
    verify(result).blameResult(inputFile,
      Arrays.asList(new BlameLine().date(DateUtils.parseDateTime("2008-10-26T16:26:44+0000")).revision("7").author("Jeremie Lagarde"),
        new BlameLine().date(DateUtils.parseDateTime("2008-10-26T16:26:44+0000")).revision("7").author("Jeremie Lagarde"),
        new BlameLine().date(DateUtils.parseDateTime("2008-10-17T11:41:50+0000")).revision("5").author("Evgeny Mandrikov"),
        new BlameLine().date(DateUtils.parseDateTime("2008-10-17T11:41:50+0000")).revision("5").author("Evgeny Mandrikov")));
  }

  // SONARSCCLC-2
  @Test
  public void dontFailOnCheckedOutFile() throws IOException {
    File source = new File(baseDir, "src/foo.xoo");
    FileUtils.write(source, "sample content");
    DefaultInputFile inputFile = new DefaultInputFile("foo", "src/foo.xoo").setLines(4).setAbsolutePath(new File(baseDir, "src/foo.xoo").getAbsolutePath());
    fs.add(inputFile);

    BlameOutput result = mock(BlameOutput.class);
    CommandExecutor commandExecutor = mock(CommandExecutor.class);

    when(commandExecutor.execute(any(Command.class), any(StreamConsumer.class), any(StreamConsumer.class), anyLong())).thenAnswer(new Answer<Integer>() {

      @Override
      public Integer answer(InvocationOnMock invocation) throws Throwable {
        StreamConsumer errConsumer = (StreamConsumer) invocation.getArguments()[2];
        errConsumer.consumeLine(
          "cleartool: Error: Operation \"annotate\" unavailable for manager \"_ms_word\" (Operation pathname was: \"C:\\IBM\\RationalSDLC\\ClearCase\\lib\\mgrs\\_ms_word\\annotate\")");
        return 1;
      }
    });

    when(input.filesToBlame()).thenReturn(Arrays.<InputFile>asList(inputFile));
    new ClearCaseBlameCommand(commandExecutor).blame(input, result);
    verifyZeroInteractions(result);
  }

  // SONARSCCLC-2
  @Test
  public void dontFailOnNewFile() throws IOException {
    File source = new File(baseDir, "src/foo.xoo");
    FileUtils.write(source, "sample content");
    DefaultInputFile inputFile = new DefaultInputFile("foo", "src/foo.xoo").setLines(4).setAbsolutePath(new File(baseDir, "src/foo.xoo").getAbsolutePath());
    fs.add(inputFile);

    BlameOutput result = mock(BlameOutput.class);
    CommandExecutor commandExecutor = mock(CommandExecutor.class);

    when(commandExecutor.execute(any(Command.class), any(StreamConsumer.class), any(StreamConsumer.class), anyLong())).thenAnswer(new Answer<Integer>() {

      @Override
      public Integer answer(InvocationOnMock invocation) throws Throwable {
        StreamConsumer errConsumer = (StreamConsumer) invocation.getArguments()[2];
        errConsumer.consumeLine("cleartool: Error: Not a vob object: \"local/version.cs\".");
        return 1;
      }
    });

    when(input.filesToBlame()).thenReturn(Arrays.<InputFile>asList(inputFile));
    new ClearCaseBlameCommand(commandExecutor).blame(input, result);
    verifyZeroInteractions(result);
  }

  // SONARSCCLC-2
  @Test
  public void failOnUnknownError() throws IOException {
    File source = new File(baseDir, "src/foo.xoo");
    FileUtils.write(source, "sample content");
    DefaultInputFile inputFile = new DefaultInputFile("foo", "src/foo.xoo").setLines(4).setAbsolutePath(new File(baseDir, "src/foo.xoo").getAbsolutePath());
    fs.add(inputFile);

    BlameOutput result = mock(BlameOutput.class);
    CommandExecutor commandExecutor = mock(CommandExecutor.class);

    when(commandExecutor.execute(any(Command.class), any(StreamConsumer.class), any(StreamConsumer.class), anyLong())).thenAnswer(new Answer<Integer>() {

      @Override
      public Integer answer(InvocationOnMock invocation) throws Throwable {
        StreamConsumer errConsumer = (StreamConsumer) invocation.getArguments()[2];
        errConsumer.consumeLine("cleartool: Error: Unknown.");
        return 1;
      }
    });

    when(input.filesToBlame()).thenReturn(Arrays.<InputFile>asList(inputFile));

    thrown.expect(IllegalStateException.class);
    thrown.expectMessage(
      "The ClearCase annotate command [cleartool annotate -out - -fmt VERSION:%Ln@@@USER:%u@@@DATE:%Nd@@@ -nheader -f src/foo.xoo] failed: cleartool: Error: Unknown.");

    new ClearCaseBlameCommand(commandExecutor).blame(input, result);
  }

}
