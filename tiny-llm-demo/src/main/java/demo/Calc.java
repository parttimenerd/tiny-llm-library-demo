package demo;

public class Calc {
    public static double eval(String expr) {
        expr = expr.replaceAll("\\s+", "");
        return new Parser(expr).parse();
    }

    static class Parser {
        private final String s;
        private int pos = 0;
        Parser(String s) { this.s = s; }

        double parse() { return parseExpr(); }

        private double parseExpr() {
            double v = parseTerm();
            while (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                char op = s.charAt(pos++);
                double r = parseTerm();
                v = op == '+' ? v + r : v - r;
            }
            return v;
        }

        private double parseTerm() {
            double v = parseFactor();
            while (pos < s.length() && (s.charAt(pos) == '*' || s.charAt(pos) == '/')) {
                char op = s.charAt(pos++);
                double r = parseFactor();
                v = op == '*' ? v * r : v / r;
            }
            return v;
        }

        private double parseFactor() {
            if (s.charAt(pos) == '(') {
                pos++;
                double v = parseExpr();
                expect(')');
                return v;
            }
            if (s.charAt(pos) == '-') { pos++; return -parseFactor(); }
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
            if (start == pos) throw new IllegalArgumentException("Unexpected char at " + pos);
            return Double.parseDouble(s.substring(start, pos));
        }

        private void expect(char c) {
            if (pos >= s.length() || s.charAt(pos) != c) throw new IllegalArgumentException("Expected '" + c + "'");
            pos++;
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java -jar calc.jar \"2+3*4\"");
            return;
        }
        String expr = String.join(" ", args);
        System.out.println(expr + " = " + eval(expr));
    }
}
