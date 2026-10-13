package io.edgeai.domain.vd;

import java.util.*;

/** Bounded arithmetic/boolean language. All SQL identifiers come from server-owned bindings. */
public final class ProcessingExpression {
    public enum Type { NUMBER, BOOLEAN, UNKNOWN }
    private sealed interface Node permits Literal, Input, Unary, Binary {
        Type type();
        String sql(Map<String,String> columns,boolean window);
    }
    private record Literal(String value,Type type) implements Node {
        public String sql(Map<String,String> columns,boolean window){return value;}
    }
    private record Input(String alias,Type type) implements Node {
        public String sql(Map<String,String> columns,boolean window){
            String column=columns.get(alias);
            if(column==null || !column.matches("x(?:[0-9]|1[0-5])"))throw new IllegalArgumentException("Invalid input column");
            if(type==Type.NUMBER)column="cast("+column+",\"float\")";
            return window?"LAST_VALUE("+column+",false)":column;
        }
    }
    private record Unary(String operator,Node operand,Type type) implements Node {
        public String sql(Map<String,String> columns,boolean window){return "("+operator+" "+operand.sql(columns,window)+")";}
    }
    private record Binary(String operator,Node left,Node right,Type type) implements Node {
        public String sql(Map<String,String> columns,boolean window){return "("+left.sql(columns,window)+" "+operator+" "+right.sql(columns,window)+")";}
    }
    private final Node root;
    private final Set<String> inputs;
    private ProcessingExpression(Node root,Set<String> inputs){this.root=root;this.inputs=Set.copyOf(inputs);}
    public Type type(){return root.type();}
    public Set<String> inputs(){return inputs;}
    public String sql(Map<String,String> columns,boolean window){return root.sql(columns,window);}
    public static ProcessingExpression parse(String expression,Map<String,Type> inputs) {
        if(expression==null || expression.isBlank() || expression.length()>1024)throw new IllegalArgumentException("Expression length out of range");
        var parser=new Parser(expression,inputs);var root=parser.expression(0,0);
        if(!parser.token.isEmpty())throw new IllegalArgumentException("Unexpected expression token");
        return new ProcessingExpression(root,parser.used);
    }
    private static final class Parser {
        private final String text;
        private final Map<String,Type> inputs;
        private final Set<String> used=new HashSet<>();
        private int offset,nodes;
        private String token;
        Parser(String text,Map<String,Type> inputs){this.text=text;this.inputs=inputs;next();}
        private void next() {
            while(offset<text.length() && Character.isWhitespace(text.charAt(offset)))offset++;
            if(offset==text.length()){token="";return;}
            int start=offset;char c=text.charAt(offset++);
            if(c>='a'&&c<='z') {
                while(offset<text.length() && (Character.isDigit(text.charAt(offset)) || text.charAt(offset)=='_' || text.charAt(offset)>='a'&&text.charAt(offset)<='z'))offset++;
            } else if(Character.isDigit(c) || c=='.') {
                while(offset<text.length() && (Character.isDigit(text.charAt(offset)) || text.charAt(offset)=='.'))offset++;
                if(offset<text.length() && (text.charAt(offset)=='e'||text.charAt(offset)=='E')) {
                    offset++;if(offset<text.length() && (text.charAt(offset)=='+'||text.charAt(offset)=='-'))offset++;
                    while(offset<text.length() && Character.isDigit(text.charAt(offset)))offset++;
                }
            } else if(offset<text.length() && Set.of("<=",">=","==","!=","&&","||").contains(text.substring(start,offset+1)))offset++;
            else if("()+-*/<>!".indexOf(c)<0)throw new IllegalArgumentException("Invalid expression character");
            token=text.substring(start,offset);
        }
        private Node expression(int min,int depth) {
            if(depth>32 || ++nodes>128)throw new IllegalArgumentException("Expression too complex");
            String value=token;next();Node left;
            if(value.equals("(")){left=expression(0,depth+1);if(!token.equals(")"))throw new IllegalArgumentException("Missing closing parenthesis");next();}
            else if(Set.of("-","+","!","not").contains(value)) {
                var operand=expression(7,depth+1);boolean logical=value.equals("!")||value.equals("not");
                require(operand.type(),logical?Type.BOOLEAN:Type.NUMBER);
                left=new Unary(logical?"NOT":value,operand,logical?Type.BOOLEAN:Type.NUMBER);
            } else if(value.equals("true")||value.equals("false"))left=new Literal(value,Type.BOOLEAN);
            else if(inputs.containsKey(value)){used.add(value);left=new Input(value,inputs.get(value));}
            else {
                try {
                    if(!value.matches("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?") || !Double.isFinite(Double.parseDouble(value)))throw new NumberFormatException();
                    left=new Literal(Double.toString(Double.parseDouble(value)),Type.NUMBER);
                } catch(NumberFormatException e){throw new IllegalArgumentException("Unknown input or invalid literal: "+value);}
            }
            while(precedence(token)>=min && !token.isEmpty()) {
                String operator=token;int precedence=precedence(operator);next();var right=expression(precedence+1,depth+1);
                Type result;
                if(Set.of("&&","||","and","or").contains(operator)){require(left.type(),Type.BOOLEAN);require(right.type(),Type.BOOLEAN);result=Type.BOOLEAN;operator=Set.of("&&","and").contains(operator)?"AND":"OR";}
                else if(Set.of("==","!=").contains(operator)) {
                    if(left.type()!=Type.UNKNOWN && right.type()!=Type.UNKNOWN && left.type()!=right.type())throw new IllegalArgumentException("Comparison types differ");
                    result=Type.BOOLEAN;operator=operator.equals("==")?"=":"<>";
                } else {require(left.type(),Type.NUMBER);require(right.type(),Type.NUMBER);result=Set.of("<","<=",">",">=").contains(operator)?Type.BOOLEAN:Type.NUMBER;}
                left=new Binary(operator,left,right,result);
            }
            return left;
        }
        private static int precedence(String operator){return switch(operator){case "||","or"->1;case "&&","and"->2;case "==","!="->3;case "<","<=",">",">="->4;case "+","-"->5;case "*","/"->6;default->-1;};}
        private static void require(Type actual,Type required){if(actual!=Type.UNKNOWN && actual!=required)throw new IllegalArgumentException("Expression type mismatch");}
    }
}
