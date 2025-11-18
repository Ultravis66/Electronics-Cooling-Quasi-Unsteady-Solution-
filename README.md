# GPU Partitioned CHT Solver
# Notes: 
- Ensure that the name matches the Java macro name here:
- public class MACRO_NAME_HERE extends StarMacro {
- Edit: The Vapor Chamber and the Fluid Volume must be set to steady state and run together if using a porous medium for your heat sink or you will get the error: No patches found. I have not found a way to resolve this issue
- ElectronicsCHTAlternatingMacro.java is a simplified working version where the Vapor Chamber and Fluid Volume are run steady-state while the PCB and Die are run Unsteady (tested and working)
  
## Advanced transient thermal simulation for GPU/electronics cooling

GPU thermal transients present a computational challenge due to vastly different timescales:
- **Airflow**: steady
- **GPU die**: Fast thermal response (10-100 ms)
- **Heat sink mass**: Slow response (1-10 seconds)

Traditional fully-coupled transient CHT requires timesteps of microsecond to maintain stability, making 10-second transients computationally prohibitive.

### Solution: Partitioned CHT Strategy

This macro implements an **alternating fluid-solid solution approach**:

1. **Fluid phase**: Converge steady-state airflow with frozen solid temperatures
2. **Solid phase**: Advance transient heat conduction with updated convective BCs
3. **Repeat**: Alternate until transient completes

### Computational Benefits

- **Maintains accuracy** for thermal events with timescale >> fluid response
- **Production-ready** for GPU design optimization

### Applications

- Power-on thermal transients
- Thermal throttling behavior prediction
- Gaming load profile simulation
- Data center cold-start analysis
- Cooling system design optimization

### Technical Details

**Timestep selection:**
- Solid timestep: 10 ms (captures die thermal time constant ~50-100 ms)
- Fluid iterations: 30 per cycle (converges flow field)
- Subcycles: 2 per major timestep (fluid-solid alternations)

**GPU stack components:**
- Silicon die (high power density)
- Silicon substrate (high conductivity)
- Vapor chamber (two-phase spreading)
- Thermal interface material (critical resistance)
- Cold plate (aluminum/copper)
- PCB (FR4, low conductivity)

### Usage
```java
// In STAR-CCM+ macro environment:
// 1. Set up GPU geometry with appropriate continua names
// 2. Define boundary conditions (power, inlet conditions)
// 3. Run this macro to execute partitioned transient solution
```
---

**Author**: Mitchell Stolk  
**Application**: GPU/Electronics Thermal Management  
**Date**: November 2025


### ⚠️ Important Note on Numerical Accuracy

This partitioned CHT approach is designed for **fast turnaround** during design exploration and early-stage optimization.  
Because the fluid and solid domains are advanced separately rather than fully coupled each timestep, this method inherently **sacrifices some numerical accuracy** and introduces **Numerical Errors**.  
For high-fidelity validation, fast fluid–thermal interactions, or safety-critical work, a **fully-coupled transient CHT simulation** should be used instead.

