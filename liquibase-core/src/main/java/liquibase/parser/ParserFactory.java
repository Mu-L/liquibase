package liquibase.parser;

import liquibase.Scope;
import liquibase.exception.DependencyException;
import liquibase.exception.ParseException;
import liquibase.parser.mapping.ParsedNodeMappingFactory;
import liquibase.parser.postprocessor.MappingPostprocessor;
import liquibase.parser.postprocessor.MappingPostprocessorFactory;
import liquibase.parser.preprocessor.ParsedNodePreprocessor;
import liquibase.parser.preprocessor.ParsedNodePreprocessorFactory;
import liquibase.plugin.AbstractPluginFactory;
import liquibase.util.StringUtil;

/**
 * Factory for {@link Parser} plugins.
 */
public class ParserFactory extends AbstractPluginFactory<Parser> {

    protected ParserFactory() {

    }

    @Override
    protected Class<Parser> getPluginClass() {
        return Parser.class;
    }

    @Override
    protected int getPriority(Parser obj, Object... args) {
        return obj.getPriority((String) args[0]);
    }

    /**
     * Returns the {@link Parser} to use for the given path.
     */
    public Parser getParser(String path) {
        return getPlugin(path);
    }


    /**
     * Converts the file at sourcePath to the passed objectType using the configured {@link Parser}(s), {@link ParsedNodePreprocessor}(s), {@link liquibase.parser.mapping.ParsedNodeMapping} and {@link liquibase.parser.postprocessor.MappingPostprocessor}(s).
     * <b>This is the primary method to use when parsing files into objects.</b>
     * If an exception is thrown, a more descriptive message will be constructed in the resulting {@link ParseException}
     */
    public <ObjectType> ObjectType parse(String sourcePath, Class<ObjectType> objectType) throws ParseException {
        Parser parser = Scope.getCurrentScope().getSingleton(ParserFactory.class).getParser(sourcePath);
        if (parser == null) {
            throw new ParseException("Cannot find parser for " + sourcePath, null);
        }
        try {

            ParsedNode rootNode = parser.parse(null, sourcePath);

            return parse(rootNode, objectType);
        } catch (ParseException e) {
            String message = e.getMessage();
            ParsedNode problemNode = e.getProblemNode();

            String parseErrorMessage = "Error parsing ";

            if (problemNode != null && problemNode.getOriginalName() != null) {
                parseErrorMessage += "\"" + problemNode.getOriginalName() + "\" in ";
            }

            if (problemNode == null || problemNode.fileName == null) {
                parseErrorMessage += sourcePath;
            } else {
                parseErrorMessage += problemNode.fileName;
            }

            if (problemNode != null && problemNode.lineNumber != null) {
                parseErrorMessage += " line " + problemNode.lineNumber;
                if (problemNode.columnNumber != null) {
                    parseErrorMessage = parseErrorMessage + ", column" + problemNode.columnNumber;
                }
            }

            String near = null;
            if (problemNode != null) {
                near = parser.describeOriginal(problemNode);
            }

            if (near == null) {
                message = parseErrorMessage + " " + message;
            } else {
                message = parseErrorMessage + "\n" + StringUtil.indent(near + "\n\n" + message);
            }


            if (problemNode != null) {
                Scope.getCurrentScope().getLog(getClass()).fine("Error parsing:\n" + StringUtil.indent(problemNode.prettyPrint()));
            }

            throw new ParseException(message, e, problemNode);
        }

    }

    public <ObjectType> ObjectType parse(ParsedNode parsedNode, Class<ObjectType> objectType) throws ParseException {
        try {
            Scope scope = Scope.getCurrentScope();
            for (ParsedNodePreprocessor preprocessor : scope.getSingleton(ParsedNodePreprocessorFactory.class).getPreprocessors()) {
//                MDC.put(LogUtil.MDC_PREPROCESSOR, preprocessor.getClass().getName());
//                try {
                    preprocessor.process(parsedNode);
//                } finally {
//                    MDC.remove(LogUtil.MDC_PREPROCESSOR);
//                }
            }

            ObjectType returnObject = scope.getSingleton(ParsedNodeMappingFactory.class).toObject(parsedNode, objectType, null, null);

            for (MappingPostprocessor postprocessor : scope.getSingleton(MappingPostprocessorFactory.class).getPostprocessors()) {
                postprocessor.process(returnObject);
            }

            return returnObject;
        } catch (DependencyException e) {
            throw new ParseException(e, null);
        }
    }

}
