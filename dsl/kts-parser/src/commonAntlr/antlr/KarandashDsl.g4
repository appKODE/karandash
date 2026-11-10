grammar KarandashDsl;

// Entry point. Top-level `name = value` (e.g. `extends = "..."`) and `if` are allowed next to blocks.
config : topLevelItem* EOF ;

topLevelItem
    : topLevelBlock
    | assignment
    | ifStatement
    ;

// Top-level blocks (rules, auth, reporters, plugins)
topLevelBlock
    : IDENTIFIER '{' statement* '}'
    ;

// Statements inside blocks
statement
    : assignment          // pattern = "..."
    | functionCall        // titleFormat { ... }
    | ifStatement         // if (condition) { ... }
    ;

// Assignments
assignment
    : IDENTIFIER '=' expression
    ;

// Function calls with optional block
functionCall
    : IDENTIFIER '(' argumentList? ')' block?
    | IDENTIFIER block
    ;

// Arguments: positional or named, trailing comma allowed
argumentList
    : argument (',' argument)* ','?
    ;

argument
    : (IDENTIFIER '=')? expression
    ;

// If statements (`else if` chains allowed)
ifStatement
    : 'if' '(' expression ')' block ('else' (block | ifStatement))?
    ;

// Blocks
block : '{' statement* '}' ;

// Expressions (alternatives listed by precedence: `!` binds tightest, then comparisons, `&&`, `||`)
expression
    : literal                                       # LiteralExpr
    | fieldAccess                                   # FieldAccessExpr
    | '!' expression                                # NotExpr
    | expression comparisonOp expression            # ComparisonExpr
    | expression '&&' expression                    # AndExpr
    | expression '||' expression                    # OrExpr
    | functionCallExpr                              # FunctionCallExpression
    | '(' expression ')'                            # ParenExpr
    ;

// Field access (mr.title, env.STRICT)
fieldAccess
    : IDENTIFIER ('.' IDENTIFIER)*
    ;

// Function call in expression
functionCallExpr
    : IDENTIFIER '(' argumentList? ')'
    ;

// Operators
comparisonOp
    : '==' | '!=' | '>' | '<' | '>=' | '<='
    | 'matches' | 'contains' | 'in'
    ;

// Literals
literal
    : STRING
    | NUMBER
    | BOOLEAN
    | listLiteral
    ;

listLiteral
    : 'listOf' '(' (expression (',' expression)* ','?)? ')'
    ;

// Tokens
BOOLEAN : 'true' | 'false' ;
IDENTIFIER : [a-zA-Z_][a-zA-Z0-9_]* ;
STRING : '"' ('\\' . | ~["\\\r\n])* '"' ;
NUMBER : '-'? [0-9]+ ('.' [0-9]+)? ;

// Whitespace and comments
WS : [ \t\r\n]+ -> skip ;
// `;` separates statements on one line: `tickets { enabled = true; maxPerMr = 10 }`
SEMI : ';' -> skip ;
LINE_COMMENT : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
