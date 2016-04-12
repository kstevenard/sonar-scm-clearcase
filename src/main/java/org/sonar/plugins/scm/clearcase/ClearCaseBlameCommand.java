/*
 * SonarQube :: Plugins :: SCM :: ClearCase
 * Copyright (C) 2014-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
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
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.scm.clearcase;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.utils.command.Command;
import org.sonar.api.utils.command.CommandExecutor;
import org.sonar.api.utils.command.StreamConsumer;
import org.sonar.api.utils.command.StringStreamConsumer;

public class ClearCaseBlameCommand extends BlameCommand {

  private static final Logger LOG = LoggerFactory.getLogger(ClearCaseBlameCommand.class);

  private static final String[] IGNORED_ERRORS = {"Operation \"annotate\" unavailable for manager",
    "Not a vob object",
    "You may not annotate a checked-out version",
    "Cannot perform operation for derived object"};
  private final CommandExecutor commandExecutor;

  public ClearCaseBlameCommand() {
    this(CommandExecutor.create());
  }

  ClearCaseBlameCommand(CommandExecutor commandExecutor) {
    this.commandExecutor = commandExecutor;
  }

  @Override
  public void blame(BlameInput input, BlameOutput output) {
    FileSystem fs = input.fileSystem();
    LOG.debug("Working directory: " + fs.baseDir().getAbsolutePath());
    for (InputFile inputFile : input.filesToBlame()) {
      blame(fs, inputFile, output);
    }

  }

  private void blame(FileSystem fs, InputFile inputFile, BlameOutput output) {
    String filename = inputFile.relativePath();
    File f = inputFile.file();
    Command cl = null;
    if (Files.isSymbolicLink(f.toPath())) {
      try {
        Path p = f.toPath().toRealPath();
        File workingDirectory = p.getParent().toFile();
        String file = p.getFileName().toString(); 
        cl = createCommandLine(workingDirectory, file);
        LOG.debug(filename +  " is a symlink to: " + workingDirectory.toString()+"/"+file);
      } catch (IOException e) {
        LOG.warn(e.getMessage(), e);
      }
    } else {
      cl = createCommandLine(fs.baseDir(), filename);
    }
    if ( cl == null ) {
      throw new IllegalStateException("Unable to create command line for " + fs.baseDir() + filename);
    }
    ClearCaseBlameConsumer consumer = new ClearCaseBlameConsumer(filename);
    StringStreamConsumer stderr = new StringStreamConsumer();

    int exitCode = execute(cl, consumer, stderr);
    if (exitCode != 0) {
      String stdErr = stderr.getOutput();
      if (ignoredError(stdErr)) {
        return;
      }
      throw new IllegalStateException("The ClearCase annotate command [" + cl.toString() + "] failed: " + stdErr);
    }
    List<BlameLine> lines = consumer.getLines();
    if (lines.size() == inputFile.lines() - 1) {
      // SONARPLUGINS-3097 ClearCase do not report blame on last empty line
      lines.add(lines.get(lines.size() - 1));
    }
    output.blameResult(inputFile, lines);
  }

  private static boolean ignoredError(String stdErr) {
    if (stdErr == null) {
      return false;
    }
    for (String msg : IGNORED_ERRORS) {
      if (stdErr.contains(msg)) {
        return true;
      }
    }
    return false;
  }

  public int execute(Command cl, StreamConsumer consumer, StreamConsumer stderr) {
    LOG.debug("Executing: " + cl);
    return commandExecutor.execute(cl, consumer, stderr, -1);
  }

  private static Command createCommandLine(File workingDirectory, String filename) {
    Command cl = Command.create("cleartool");
    cl.setDirectory(workingDirectory);
    cl.addArgument("annotate");

    StringBuilder format = new StringBuilder();
    format.append("VERSION:%Ln@@@");
    format.append("USER:%u@@@");
    format.append("DATE:%Nd@@@");
    cl.addArgument("-out");
    cl.addArgument("-");
    cl.addArgument("-fmt");
    cl.addArgument(format.toString());
    cl.addArgument("-nheader");
    cl.addArgument("-f");
    cl.addArgument(filename);

    return cl;
  }

}
