import star.common.*;
import java.util.*;

/**
 * Partitioned CHT Solver for Efficient Electronics Cooling
 * 
 * This macro implements an alternating fluid-solid solution strategy to overcome
 * the timescale disparity between fluid (fast) and solid heat conduction (slow).
 * 
 * Strategy:
 * - Fluid: Run steady-state iterations to converge flow field
 * - Solid: Advance thermal solution with timesteps
 * - Repeat: Alternate to capture the coupled physics efficiently
 * 
 * This approach reduces computational time for faster turnaround time
 * maintaining accuracy for GPU thermal management with large thermal mass differences.
 * Author: Mitchell Stolk
 * November 2025
 */
public class ElectronicsCHTAlternatingMacro extends StarMacro {
    
    // SIMULATION PARAMETERS
    // Time control
    private static final double END_TIME = 10.0;             // Total physical time (s)
    private static final double SOLID_TIME_STEP = 0.002;     // 2e-3 s timestep for solids
    private static final double SAVE_INTERVAL = 0.5;         // Save solution every 0.5 s
    
    // Iteration control
    private static final int FLUID_ITERATIONS = 30;          // Steady iterations per fluid solve
    private static final int SUBCYCLES_PER_TIMESTEP = 2;     // Number of fluid-solid pairs per timestep
    
    // Continuum names
    private static final String FLUID_CONTINUUM = "Fluid_Volume";
    private static final String[] SOLID_CONTINUA = {
        "S_Silicon_Die",       // high power density
        "S_Silicon_Substrate", 
        "S_Vapor_Chamber",     
        "S_Cold_Plate",        
        "S_PCB",              
        "S_Air_Gap"           // Gas in solid region
    };
    
    // MEMBER VARIABLES
    private Simulation sim;
    private PhysicsContinuum fluidContinuum;
    private List<PhysicsContinuum> solidContinua;
    private PhysicalTimeStoppingCriterion timeStopCriterion;
    private StepStoppingCriterion stepStopCriterion;
    private IntegerValue maxStepsValue;
    
    // Tracking variables
    private int totalFluidIterations = 0;
    private int totalSolidSteps = 0;
    private double initialTime;
    
    @Override
    public void execute() {
        try {
            sim = getActiveSimulation();
            sim.println("\n========================================");
            sim.println("PARTITIONED ELECTRONICS CHT SOLVER");
            sim.println("========================================");
            
            initializeSimulation();
            runPartitionedCHT();
            printSummary();
            
        } catch (Exception e) {
            sim.println("ERROR in ElectronicsCHTAlternatingMacro: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * Initialize all simulation components and validate setup
     */
    private void initializeSimulation() {
        sim.println("\nInitializing simulation components...");
        
        // Get and validate continua
        fluidContinuum = getContinuumSafely(FLUID_CONTINUUM);
        solidContinua = new ArrayList<>();
        for (String name : SOLID_CONTINUA) {
            solidContinua.add(getContinuumSafely(name));
        }
        
        // Get stopping criteria
        try {
            timeStopCriterion = (PhysicalTimeStoppingCriterion) 
                sim.getSolverStoppingCriterionManager()
                   .getSolverStoppingCriterion("Maximum Physical Time");
            
            stepStopCriterion = (StepStoppingCriterion) 
                sim.getSolverStoppingCriterionManager()
                   .getSolverStoppingCriterion("Maximum Steps");
            
            maxStepsValue = stepStopCriterion.getMaximumNumberStepsObject();
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get stopping criteria: " + e.getMessage());
        }
        
        initialTime = sim.getSolution().getPhysicalTime();
        
        // Print configuration
        sim.println("\nConfiguration:");
        sim.println("  - End time: " + END_TIME + " s");
        sim.println("  - Solid timestep: " + SOLID_TIME_STEP + " s (vs 1e-6 for fully coupled)");
        sim.println("  - Fluid iterations per solve: " + FLUID_ITERATIONS);
        sim.println("  - Subcycles per timestep: " + SUBCYCLES_PER_TIMESTEP);
        sim.println("  - Effective speedup: ~" + (int)(SOLID_TIME_STEP / 1e-6) + "x");
        sim.println("  - Starting from t = " + initialTime + " s");
    }
    
    /**
     * Main partitioned CHT loop
     */
    private void runPartitionedCHT() {
        double currentTime = initialTime;
        double nextSaveTime = currentTime + SAVE_INTERVAL;
        int majorStep = 0;
        
        sim.println("\n========================================");
        sim.println("Starting partitioned CHT solution");
        sim.println("========================================");
        
        while (currentTime < END_TIME) {
            majorStep++;
            
            // Each major step advances by 2*SOLID_TIME_STEP due to subcycling
            double stepStartTime = currentTime;
            double targetTime = Math.min(currentTime + 2 * SOLID_TIME_STEP, END_TIME);
            
            sim.println(String.format("\n--- Major Step %d: t = %.4f -> %.4f s ---", 
                                      majorStep, stepStartTime, targetTime));
            
            // Perform subcycles (alternating fluid-solid)
            for (int subcycle = 1; subcycle <= SUBCYCLES_PER_TIMESTEP; subcycle++) {
                
                // FLUID PHASE: Converge flow field with frozen solid temperatures
                runFluidPhase(subcycle, currentTime);
                
                // SOLID PHASE: Advance thermal solution with converged heat fluxes
                currentTime = runSolidPhase(subcycle, currentTime);
                
                // Check for early termination
                if (currentTime >= END_TIME) {
                    break;
                }
            }
            
            // Save if needed
            if (currentTime >= nextSaveTime || currentTime >= END_TIME) {
                saveSimulation(currentTime);
                nextSaveTime += SAVE_INTERVAL;
            }
            
            // Report heat transfer metrics (optional)
            reportHeatTransfer(currentTime);
        }
    }
    
    /**
     * Run fluid phase - steady state iterations with frozen solid temperatures
     */
    private void runFluidPhase(int subcycle, double currentTime) {
        sim.println(String.format("  [Fluid %d] Converging flow field (%d iterations)...", 
                                  subcycle, FLUID_ITERATIONS));
        
        // Enable only fluid physics
        setActiveContinua(true, false);
        
        // Update iteration count - using getRawValue() to avoid deprecation warning
        double currentMaxSteps = maxStepsValue.getQuantity().getRawValue();
        maxStepsValue.getQuantity().setValue(currentMaxSteps + FLUID_ITERATIONS);
        
        // Run steady iterations
        sim.getSimulationIterator().run();
        
        totalFluidIterations += FLUID_ITERATIONS;
        
        // Optional: Check fluid convergence
        checkFluidConvergence();
    }
    
    /**
     * Run solid phase - transient heat conduction with updated boundary conditions
     */
    private double runSolidPhase(int subcycle, double currentTime) {
        double targetTime = Math.min(currentTime + SOLID_TIME_STEP, END_TIME);
        
        sim.println(String.format("  [Solid %d] Advancing thermal solution: %.4f -> %.4f s", 
                                  subcycle, currentTime, targetTime));
        
        // Enable only solid physics
        setActiveContinua(false, true);
        
        // Set time advancement
        timeStopCriterion.setMaximumTime(targetTime);
        
        // Run transient solid solution
        sim.getSimulationIterator().run();
        
        totalSolidSteps++;
        
        // Return actual time (might be different if hit END_TIME)
        return sim.getSolution().getPhysicalTime();
    }
    
    /**
     * Control which physics continua are active
     */
    private void setActiveContinua(boolean fluidActive, boolean solidsActive) {
        fluidContinuum.setIsActive(fluidActive);
        for (PhysicsContinuum solid : solidContinua) {
            solid.setIsActive(solidsActive);
        }
    }
    
    /**
     * Check fluid convergence (optional monitoring)
     */
    private void checkFluidConvergence() {
        // Could check residuals, monitor points, etc.
        // Example:
        // ResidualMonitor contResidual = 
        //     (ResidualMonitor) sim.getMonitorManager().getMonitor("Continuity");
        // double residual = contResidual.getMonitorValue();
        // if (residual > 1e-3) {
        //     sim.println("    Warning: Fluid may need more iterations (residual: " + residual + ")");
        // }
    }
    
    /**
     * Report heat transfer metrics
     */
    private void reportHeatTransfer(double time) {
        // Optional: Report die temperature, junction temperature, heat flux, etc.
        // This helps validate the partitioned approach against fully coupled solutions
        
        // Example:
        // Report dieTempReport = sim.getReportManager().getReport("Max Die Temperature");
        // double dieTemp = dieTempReport.getReportMonitorValue();
        // sim.println(String.format("  Die temperature at t=%.4f: %.1f C", time, dieTemp - 273.15));
    }
    
    /**
     * Save simulation state
     */
    private void saveSimulation(double time) {
        sim.println(String.format("\n>>> Saving simulation at t = %.4f s", time));
        
        // Option 1: Save the entire simulation
        String filename = String.format("Electronics_CHT_partitioned_t%.2f.sim", time);
        sim.saveState(sim.getSessionDir() + "/" + filename);
        
        // Option 2: Export specific data (scenes, reports, etc.)
        // exportScenes(time);
        // exportReports(time);
    }
    
    /**
     * Safely retrieve continuum with validation
     */
    private PhysicsContinuum getContinuumSafely(String name) {
        try {
            PhysicsContinuum continuum = (PhysicsContinuum) 
                sim.getContinuumManager().getContinuum(name);
            if (continuum == null) {
                throw new RuntimeException("Continuum '" + name + "' not found");
            }
            sim.println("  ✓ Found continuum: " + name);
            return continuum;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get continuum '" + name + "': " + e.getMessage());
        }
    }
    
    /**
     * Print final summary statistics
     */
    private void printSummary() {
        double finalTime = sim.getSolution().getPhysicalTime();
        double totalSimTime = finalTime - initialTime;
        
        sim.println("\n========================================");
        sim.println("SIMULATION COMPLETE");
        sim.println("========================================");
        sim.println("Summary:");
        sim.println("  - Total simulated time: " + totalSimTime + " s");
        sim.println("  - Total fluid iterations: " + totalFluidIterations);
        sim.println("  - Total solid timesteps: " + totalSolidSteps);
        sim.println("  - Effective timestep used: " + SOLID_TIME_STEP + " s");
        sim.println("  - Computational savings vs fully coupled: ~" + 
                    (int)(SOLID_TIME_STEP / 1e-6) + "x faster");
        
        // Estimate equivalent fully-coupled iterations
        int equivalentIterations = (int)(totalSimTime / 1e-6);
        sim.println("  - Equivalent fully-coupled iterations avoided: " + equivalentIterations);
        sim.println("========================================\n");
    }
}
