package org.openrepose.gradle.plugins.jaxb.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.openrepose.gradle.plugins.jaxb.JaxbPlugin
import org.openrepose.gradle.plugins.jaxb.ant.AntExecutor
import org.openrepose.gradle.plugins.jaxb.converter.NamespaceToEpisodeConverter
import org.openrepose.gradle.plugins.jaxb.resolver.EpisodeDependencyResolver
import org.openrepose.gradle.plugins.jaxb.resolver.XjcResolver
import org.openrepose.gradle.plugins.jaxb.tree.TreeManager
import org.openrepose.gradle.plugins.jaxb.tree.TreeNode
import org.openrepose.gradle.plugins.jaxb.xsd.XsdNamespace

/**
 * Plugin's task to run xsd files through ant {@code xjc} task.
 */
class JaxbXjc extends DefaultTask {
  static final Logger log = Logging.getLogger(JaxbXjc.class)

  /**
   * Contains and manages the xsd dependency tree.
   */
  @Internal
  TreeManager manager

  /**
   * Directory where all episode files are located.
   */
  private File episodeDir

  /**
   * Directory where the generated java files from xjc would go
   * Usually {@code <project-root>/build/generated-sources/xjc}
   */
  @OutputDirectory
  File generatedFilesDirectory

  /**
   * Xsd's defined under {@code xsdDir}.
   */
  @InputFiles
  FileCollection xsds

  /**
   * Directory containing all the xsds to be parsed.
   */
  @InputDirectory
  File schemasDirectory

  /**
   * User defined custom bindings to bind in ant task.
   */
  @InputFiles
  FileCollection bindings

  /**
   * Executes the ant {@code xjc} task.
   */
  @Internal
  AntExecutor xjc

  /**
   * Converts xsd namespaces to episode file names.
   */
  @Internal
  NamespaceToEpisodeConverter episodeConverter

  /**
   * Resolves the input files for the ant task to parse.
   */
  @Internal
  XjcResolver xjcResolver

  /**
   * Resolves a node's dependencies to episode files to bind in ant task.
   */
  @Internal
  EpisodeDependencyResolver dependencyResolver

  /**
   * Executes this task.
   * Starts at dependency tree root (no dependencies) and parses each node's
   * information to pass to the ant task until there are no more nodes left to
   * parse.
   */
  @TaskAction
  void start() {
    // Delete the directory from the last run if the UP-TO-DATE check fails.
    getGeneratedFilesDirectory().deleteDir()
    getGeneratedFilesDirectory().mkdirs()

    def manager = getManager()

    if (!getBindings().isEmpty()) {
      // have bindings, can't use Node processing, one xjc run and exit
      log.info("bindings are present, running ant xjc task on all xsds in '{}' and then exiting!", getSchemasDirectory())
      def namespace = manager.treeRoot.pop().data.namespace ?: "random-namespace"
      xjc(getXsds(), [], getEpisodeFile(namespace))
      return
    }

    log.info("jaxb: attempting to parse '{}' nodes in tree, base nodes are '{}'",
             manager.managedNodes.size(), manager.treeRoot)
    def nodes = manager.treeRoot
    while(nodes) {
      log.info("parsing '{}' nodes '{}'", nodes.size(), nodes)
      nodes.each { node -> parseNode(node) }
      nodes = manager.getNextDescendants(nodes)
    }
  }

  /**
   * Parses a {@code TreeNode} and passes data through to ant task.
   * 
   * @param node  tree node to run through ant task
   */
  def parseNode(TreeNode<XsdNamespace> node) {
    log.info("resolving necessary information for node '{}'", node)
    def episodes = getDependencyResolver().resolve(node, getEpisodeConverter(), getEpisodeDirectory())
    def xsdFiles = getXjcResolver().resolve(node.data)

    log.info("running ant xjc task on node '{}'", node)
    xjc(xsdFiles, episodes, getEpisodeFile(node.data.namespace))
  }

  def xjc(xsdFiles, episodes, episodeFile) {
    def jaxbConfig = project.configurations[JaxbPlugin.JAXB_CONFIGURATION_NAME]
    def xjcConfig = project.configurations[JaxbPlugin.XJC_CONFIGURATION_NAME]
    log.debug("episodes are '{}' is empty '{}'", episodes, episodes.isEmpty())
    new File((String)project.jaxb.xjc.destinationDir).mkdirs()
    getXjc().execute(
            ant,
            project.jaxb.xjc,
            jaxbConfig.asPath,
            xjcConfig.asPath,
            project.files(xsdFiles),
            getBindings(),
//            project.files(episodes),
            episodeFile
    )
  }

  @OutputDirectory
  def getEpisodeDirectory() {
    if (project.jaxb.xjc.generateEpisodeFiles) {
      this.episodeDir
    }
  }

  def setEpisodeDirectory(File file) {
    if (project.jaxb.xjc.generateEpisodeFiles) {
      this.episodeDir = file
    }
  }

  def getEpisodeFile(xsdNamespace) {
    if (project.jaxb.xjc.generateEpisodeFiles) {
      def episode = getEpisodeConverter().convert(xsdNamespace)
      new File(getEpisodeDirectory(), episode)
    }
  }
}
