import { EditorProvider } from "./components/editor/EditorProvider";
import { EditorLayout } from "./components/editor/EditorLayout";
import { EvaluatorProvider } from "./context/EvaluatorContext";

function App() {
  return (
    <EvaluatorProvider initialType="direct">
      <EditorProvider>
        <EditorLayout />
      </EditorProvider>
    </EvaluatorProvider>
  );
}

export default App;
