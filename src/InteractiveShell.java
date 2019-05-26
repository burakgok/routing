
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.function.Consumer;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.plaf.ScrollBarUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.Style;
import javax.swing.text.StyledDocument;

/**
 * @author Burak Gök
 */
public class InteractiveShell {
    private static final Font FONT = new Font("Consolas", Font.PLAIN, 16);
    private final Output output = new Output();
    private final StyledDocument document;
    private Renderer renderer;
    
    public InteractiveShell(Consumer<String> consumer) {
        JTextPane console = new JTextPane();
        console.setFont(FONT);
        console.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        console.setEditable(false);
        console.setPreferredSize(new Dimension(800, 400));
        
        document = console.getStyledDocument();
        renderer = (text, output) -> output.append(text, null);
        
        console.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    try {
                        int offset = console.viewToModel(e.getPoint());
                        Rectangle rect = console.modelToView(offset);
                        if (e.getPoint().distance(rect.x, rect.y) < 20) {
                            Element map = document.getDefaultRootElement();
                            int line = map.getElementIndex(offset);
                            Element lineElem = map.getElement(line);
                            int start = lineElem.getStartOffset();
                            int end = lineElem.getEndOffset();
                            console.select(start, end);
                        }
                    } catch (BadLocationException ex) {
                        System.err.println(ex.getMessage());
                    } finally {
                        e.consume();
                    }
                }
            }
        });
        
        JScrollPane scrollPane = new ModernScrollPane(console);
        
        JTextArea prompt = new JTextArea(1, 0);
        prompt.setFont(FONT);
        prompt.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color.gray),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        
        prompt.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (e.getModifiersEx() == KeyEvent.SHIFT_DOWN_MASK)
                        prompt.append("\n");
                    else {
                        consumer.accept(prompt.getText());
                        prompt.setText("");
                        e.consume();
                    }
                } else super.keyPressed(e);
            }
        });
        
        System.setOut(new PrintStream(new OutputStream() {
            private final StringBuilder sb = new StringBuilder();
            public void write(byte[] buffer, int offset, int length)
                    throws IOException {
                String text = new String(buffer, offset, length);
                sb.append(text);
                if (System.lineSeparator().equals(text)) {
                    String line = sb.toString();
//                    SwingUtilities.invokeLater(() ->
//                        renderer.render(line, output));
                    renderer.render(line, output);
                    sb.delete(0, sb.length());
                }
            }
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }
        }));
        
        JFrame frame = new JFrame("Cooperative Routing Protocol");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.add(scrollPane);
        frame.add(prompt, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
    
    public Style addStyle(String name, Style parent) {
        return document.addStyle(name, parent);
    }
    public Output getOutput() {
        return output;
    }
    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }
    
    public interface Renderer {
        void render(String text, Output console);
    }
    
    public class Output {
        public void append(String text, Style style) {
            try {
                document.insertString(document.getLength(), text, style);
            } catch (BadLocationException ex) {
                System.out.println(ex.getMessage());
            }
        }
    }
    
    private static class ModernScrollPane extends JScrollPane {
        public ModernScrollPane(Component view) {
            super(view);
            initStyle();
        }
        
        private void initStyle() {
            setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            setBorder(null);
            setComponentZOrder(getVerticalScrollBar(), 0);
            setComponentZOrder(getViewport(), 1);
            getVerticalScrollBar().setOpaque(false);
            setLayout(LAYOUT);
            getVerticalScrollBar().setUI(UI);
        }
        
        private static final ScrollPaneLayout LAYOUT = new ScrollPaneLayout() {
            public void layoutContainer(Container parent) {
                Rectangle availR = ((JScrollPane) parent).getBounds();
                availR.x = availR.y = 0;

                Insets insets = parent.getInsets();
                availR.x = insets.left;
                availR.y = insets.top;
                availR.width  -= insets.left + insets.right;
                availR.height -= insets.top  + insets.bottom;
                if (viewport != null)
                    viewport.setBounds(availR);

                Rectangle vsbR = new Rectangle();
                vsbR.width  = 10;
                vsbR.height = availR.height;
                vsbR.x = availR.x + availR.width - vsbR.width;
                vsbR.y = availR.y;
                if (vsb != null) {
                    vsb.setVisible(true);
                    vsb.setBounds(vsbR);
                }
            }
        };
        
        private static final ScrollBarUI UI = new BasicScrollBarUI() {
            private final Dimension empty = new Dimension();
            private final Color rollOverColor = new Color(160, 160, 160);
            protected JButton createDecreaseButton(int orientation) {
                JButton button = new JButton();
                button.setPreferredSize(empty);
                return button;
            }
            protected JButton createIncreaseButton(int orientation) {
                JButton button = new JButton();
                button.setPreferredSize(empty);
                return button;
            }
            protected void paintTrack(Graphics g, JComponent c, Rectangle r) {}
            protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                    RenderingHints.VALUE_ANTIALIAS_ON);
                JScrollBar sb = (JScrollBar) c;
                if (!sb.isEnabled() || r.width > r.height)
                    return;
                
                Color color;
                if (isDragging) color = Color.gray;
                else if (isThumbRollover()) color = rollOverColor;
                else color = Color.lightGray;
                
                g2.setPaint(color);
                g2.fillRoundRect(r.x, r.y, r.width, r.height, 10, 10);
                g2.dispose();
            }
            protected void setThumbBounds(int x, int y, int width, int height) {
                super.setThumbBounds(x, y, width, height);
                scrollbar.repaint();
            }
        };
    }
}
