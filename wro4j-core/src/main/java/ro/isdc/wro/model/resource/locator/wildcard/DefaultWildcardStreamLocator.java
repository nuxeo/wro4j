/**
 * Copyright Alex Objelean
 */
package ro.isdc.wro.model.resource.locator.wildcard;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.AndFileFilter;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.FileEqualsFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.isdc.wro.util.Function;

/**
 * Default implementation of {@link WildcardStreamLocator}.
 *
 * @author Alex Objelean
 * @author Paul Podgorsek
 */
public class DefaultWildcardStreamLocator
    implements WildcardStreamLocator, WildcardExpanderHandlerAware {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultWildcardStreamLocator.class);

  /**
   * Character to distinguish wildcard inside the uri.
   */
  public static final String RECURSIVE_WILDCARD = "**";

  /**
   * Character to distinguish wildcard inside the uri. If the file name contains
   * '*' or '?' character, it is considered a wildcard.
   * <p>
   * A string is considered to contain wildcard if it doesn't start with http(s)
   * and contains at least one of the following characters: [?*].
   */
  private static final String WILDCARD_REGEX = "^(?:(?!http))(.)*[\\*\\?]+(.)*";

  /**
   * Regex used to identify the query path from the provided path.
   */
  private static final String REGEX_QUERY_PATH = "\\?.*";

  /**
   * Ensures File's natural ordering across different platforms.
   */
  private static final Comparator<File> ALPHABETIC_FILE_COMPARATOR = new Comparator<File>() {
    public int compare(final File o1, final File o2) {
      return o1.getPath().compareTo(o2.getPath());
    }
  };

  /**
   * Removes the query path from the path which potentially could be treated as a
   * path containing wildcard special characters.
   *
   * @param path to strip the query path from.
   * @return a path with the query path removed.
   */
  public static String stripQueryPath(final String path) {
    return path.replaceFirst(REGEX_QUERY_PATH, "");
  }

  /**
   * Responsible for expanding wildcards, in other words for replacing one
   * wildcard with a set of associated files.
   */
  private Function<Collection<File>, Void> wildcardExpanderHandler;

  /**
   * {@inheritDoc}
   */
  public boolean hasWildcard(final String uri) {
    return uri.matches(WILDCARD_REGEX);
  }

  /**
   * {@inheritDoc}
   */
  public InputStream locateStream(final String uri, final File folder)
      throws IOException {
    final Collection<File> files = findMatchedFiles(new WildcardContext(uri, folder));
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final File file : files) {
      if (file.isFile()) {
        final InputStream is = new FileInputStream(file);
        IOUtils.copy(is, out);
        is.close();
      } else {
        LOG.debug("Ignoring folder: " + file);
      }
    }
    return new BufferedInputStream(new ByteArrayInputStream(out.toByteArray()));
  }

  /**
   * @return a collection of files found inside a given folder for a search uri
   *         which contains a wildcard.
   */
  private Collection<File> findMatchedFiles(final WildcardContext wildcardContext) throws IOException {

    validate(wildcardContext);

    // Note: since commons-io 3.11.0 the traversed folders are also selected, even
    // if files are selected on a wildcard like "**.js"
    IOFileFilter fileFilter = new AndFileFilter(FileFileFilter.INSTANCE,
        new WildcardFileFilter(wildcardContext.getWildcard()),
        new FileEqualsFileFilter(wildcardContext.getFolder()).negate());

    Collection<File> files = FileUtils.listFiles(wildcardContext.getFolder(), fileFilter,
        getFolderFilter(wildcardContext.getWildcard()));

    final Set<File> allFiles = new TreeSet<>(ALPHABETIC_FILE_COMPARATOR);
    allFiles.addAll(files);

    triggerWildcardExpander(allFiles, wildcardContext);

    return allFiles;
  }

  /**
   * Validates arguments used by
   * {@link DefaultWildcardStreamLocator#findMatchedFiles(WildcardContext)}
   * method.
   *
   * @throws IOException if supplied arguments are invalid or cannot be handled by
   *                     this locator.
   */
  private void validate(final WildcardContext wildcardContext) throws IOException {
    Validate.notNull(wildcardContext);
    final String uri = wildcardContext.getUri();
    final File folder = wildcardContext.getFolder();

    if (uri == null || folder == null || !folder.isDirectory()) {
      final StringBuffer message = new StringBuffer("Invalid folder provided");
      if (folder != null) {
        message.append(", with path: ").append(folder.getPath());
      }
      message.append(", with fileNameWithWildcard: ").append(uri);
      throw new IOException(message.toString());
    }
    if (!hasWildcard(uri)) {
      throw new IOException("No wildcard detected for the uri: " + uri);
    }
    LOG.debug("uri: {}", uri);
    LOG.debug("folder: {}", folder.getPath());
    LOG.debug("wildcard: {}", wildcardContext.getWildcard());
  }

  /**
   * Uses the wildcardExpanderHandler to process all found files and directories.
   *
   * @param allFiles a collection of all files and folders found during wildcard
   *                 matching.
   * @VisibleForTestOnly
   */
  void triggerWildcardExpander(final Collection<File> allFiles, final WildcardContext wildcardContext)
      throws IOException {
    LOG.debug("wildcard resources: {}", allFiles);
    if (allFiles.isEmpty()) {
      final String message = String.format("No resource found for wildcard: %s", wildcardContext.getWildcard());
      LOG.warn(message);
      throw new IOException(message);
    }
    if (wildcardExpanderHandler != null) {
      try {
        wildcardExpanderHandler.apply(allFiles);
      } catch (final IOException e) {
        // preserve exception type if the exception is already an IOException
        throw e;
      } catch (final Exception e) {
        LOG.debug("wildcard expanding error. Reporting original exception", e);
        throw new IOException("Exception during expanding wildcard: " + e.getMessage());
      }
    }
  }

  /**
   * @param wildcard to use to determine if the folder filter should be recursive
   *                 or not.
   * @return filter to be used for folders.
   */
  private static IOFileFilter getFolderFilter(final String wildcard) {
    final boolean recursive = wildcard.contains(RECURSIVE_WILDCARD);
    return recursive ? TrueFileFilter.INSTANCE : FalseFileFilter.INSTANCE;
  }

  /**
   * {@inheritDoc}
   */
  public void setWildcardExpanderHandler(final Function<Collection<File>, Void> handler) {
    this.wildcardExpanderHandler = handler;
  }
}
